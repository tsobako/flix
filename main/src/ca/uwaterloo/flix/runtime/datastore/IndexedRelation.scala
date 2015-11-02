package ca.uwaterloo.flix.runtime.datastore

import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.runtime.{Solver, Value}
import ca.uwaterloo.flix.util.BitOps

import scala.annotation.switch
import scala.collection.mutable

/**
 * Companion object for the [[IndexedRelation]] class.
 */
object IndexedRelation {

  /**
   * A common super-type for keys.
   */
  sealed trait Key

  /**
   * A key with one value.
   */
  final class Key1(val v1: Value) extends Key {
    override def equals(o: scala.Any): Boolean = o match {
      case that: Key1 => this.v1 == that.v1
      case _ => false
    }

    override val hashCode: Int = 3 * v1.hashCode()
  }

  /**
   * A key with two values.
   */
  final class Key2(val v1: Value, val v2: Value) extends Key {
    override def equals(o: scala.Any): Boolean = o match {
      case that: Key2 =>
        this.v1 == that.v1 &&
          this.v2 == that.v2
      case _ => false
    }

    override val hashCode: Int = 3 * v1.hashCode() + 5 * v2.hashCode()
  }

  /**
   * A key with three values.
   */
  final class Key3(val v1: Value, val v2: Value, val v3: Value) extends Key {
    override def equals(o: scala.Any): Boolean = o match {
      case that: Key3 =>
        this.v1 == that.v1 &&
          this.v2 == that.v2 &&
          this.v3 == that.v3
      case _ => false
    }

    override val hashCode: Int = 3 * v1.hashCode() + 5 * v2.hashCode() + 7 * v3.hashCode()
  }

  /**
   * Constructs a new indexed relation for the given `relation` and `indexes`.
   */
  def mk(relation: TypedAst.Collection.Relation, indexes: Set[Seq[Int]])(implicit sCtx: Solver.SolverContext) = {
    // translate indexes into their binary representation.
    val idx = indexes map {
      case columns => BitOps.setBits(vec = 0, bits = columns)
    }
    // assume a default index on the first column.
    val defaultIndex = BitOps.setBit(vec = 0, bit = 0)

    new IndexedRelation(relation, idx + defaultIndex, defaultIndex)
  }

}

/**
 * A class that stores a relation in an indexed database. An index is a subset of the columns encoded in binary.
 *
 * An index on the first column corresponds to 0b0000...0001.
 * An index on the first and third columns corresponds to 0b0000...0101.
 *
 * @param relation the relation.
 * @param indexes the indexes.
 * @param default the default index.
 */
// TODO: Specialize?
final class IndexedRelation(relation: TypedAst.Collection.Relation, indexes: Set[Int], default: Int)(implicit sCtx: Solver.SolverContext) extends IndexedCollection {

  /**
   * A map from indexes to keys to rows of values.
   */
  private val store = mutable.Map.empty[Int, mutable.Map[IndexedRelation.Key, mutable.Set[Array[Value]]]]

  /**
   * Records the number of indexed lookups, i.e. exact lookups.
   */
  private var indexedLookups = 0

  /**
   * Records the number of indexed scans, i.e. table scans which can use an index.
   */
  private var indexedScans = 0

  /**
   * Records the number of full scans, i.e. table scans which cannot use an index.
   */
  private var fullScans = 0

  /**
   * Initialize the store for all indexes.
   */
  for (idx <- indexes) {
    store(idx) = mutable.Map.empty
  }

  /**
   * Returns the size of the relation.
   */
  def getSize: Int = scan.size

  /**
   * Returns the number of indexed lookups.
   */
  def getNumberOfIndexedLookups: Int = indexedLookups

  /**
   * Returns the number of indexed scans.
   */
  def getNumberOfIndexedScans: Int = indexedScans

  /**
   * Returns the number of full scans.
   */
  def getNumberOfFullScans: Int = fullScans

  /**
   * Processes a new inferred `fact`.
   *
   * Adds the fact to the relation. All entries in the fact must be non-null.
   *
   * Returns `true` iff the fact did not already exist in the relation.
   */
  def inferredFact(fact: Array[Value]): Boolean = {
    if (lookup(fact).isEmpty) {
      newFact(fact)
      return true
    }
    false
  }

  /**
   * Updates all indexes and tables with a new fact `f`.
   */
  private def newFact(fact: Array[Value]): Unit = {
    // loop through all the indexes and update the tables.
    for (idx <- indexes) {
      val key = keyOf(idx, fact)
      val table = store(idx).getOrElseUpdate(key, mutable.Set.empty[Array[Value]])
      table += fact
    }
  }

  /**
   * Performs a lookup of the given pattern `pat`. 
   *
   * If the pattern contains `null` entries these are interpreted as free variables.
   *
   * Returns an iterator over the matching rows.
   */
  def lookup(pat: Array[Value]): Iterator[Array[Value]] = {
    // case 1: Check if there is an exact index.
    var idx = exactIndex(pat)
    if (idx != 0) {
      // an exact index exists. Use it.
      indexedLookups += 1
      val key = keyOf(idx, pat)
      store(idx).getOrElseUpdate(key, mutable.Set.empty).iterator
    } else {
      // case 2: No exact index available. Check if there is an approximate index.
      idx = approxIndex(pat)
      val table = if (idx != 0) {
        // case 2.1: An approximate index exists. Use it.
        indexedScans += 1
        val key = keyOf(idx, pat)
        store(idx).getOrElseUpdate(key, mutable.Set.empty).iterator
      } else {
        // case 2.2: No usable index. Perform a full table scan.
        fullScans += 1
        scan
      }

      // filter rows returned by a partial index or table scan.
      table filter {
        case row => matchRow(pat, row)
      }
    }
  }

  /**
   * Returns the key for the given index `idx` and pattern `pat`.
   */
  private def keyOf(idx: Int, pat: Array[Value]): IndexedRelation.Key = {
    val columns = Integer.bitCount(idx)
    val i1 = idx
    (columns: @switch) match {
      case 1 =>
        val c1 = BitOps.positionOfLeastSignificantBit(i1)
        new IndexedRelation.Key1(pat(c1))

      case 2 =>
        val c1 = BitOps.positionOfLeastSignificantBit(i1)
        val i2 = BitOps.clearBit(vec = i1, bit = c1)
        val c2 = BitOps.positionOfLeastSignificantBit(i2)
        new IndexedRelation.Key2(pat(c1), pat(c2))

      case 3 =>
        val c1 = BitOps.positionOfLeastSignificantBit(i1)
        val i2 = BitOps.clearBit(vec = i1, bit = c1)
        val c2 = BitOps.positionOfLeastSignificantBit(i2)
        val i3 = BitOps.clearBit(vec = i2, bit = c2)
        val c3 = BitOps.positionOfLeastSignificantBit(i3)
        new IndexedRelation.Key3(pat(c1), pat(c2), pat(c3))

      case _ => throw new RuntimeException("Indexes on more than three keys are currently not supported.")
    }
  }

  /**
   * Returns an index matching all the non-null columns in the given pattern `pat`.
   *
   * Returns zero if no such index exists.
   */
  private def exactIndex(pat: Array[Value]): Int = {
    var index = 0
    var i = 0
    while (i < pat.length) {
      if (pat(i) != null) {
        // the i'th column in the pattern exists, so it should be in the index.
        index = BitOps.setBit(vec = index, bit = i)
      }
      i = i + 1
    }

    if (indexes contains index) index else 0
  }

  /**
   * Returns an approximate index matching all the non-null columns in the given pattern `pat`.
   *
   * Returns zero if no such index exists.
   */
  private def approxIndex(pat: Array[Value]): Int = {
    // the result index. Defaults to zero representing that no usable index exists.
    var result: Int = 0
    // loop through all available indexes looking for the first partially matching index.
    val iterator = indexes.iterator
    while (iterator.hasNext) {
      val index = iterator.next()
      var i = 0
      var usable = true
      while (i < pat.length) {
        if (BitOps.getBit(vec = index, bit = i) && pat(i) == null) {
          // the index requires the i'th column to be non-null, but it is null in the pattern.
          // thus this specific index is not usable.
          usable = false
          i = pat.length
        }
        i = i + 1
      }

      // heuristic: If multiple indexes are usable, choose the one with the most columns.
      if (usable) {
        if (Integer.bitCount(result) < Integer.bitCount(index)) {
          result = index
        }
      }
    }

    // return result
    return result
  }

  /**
   * Returns all rows in the relation using a table scan.
   */
  def scan: Iterator[Array[Value]] = store(default).iterator.flatMap {
    case (key, value) => value
  }

  /**
   * Returns `true` if the given pattern `pat` matches the given `row`.
   *
   * A pattern matches if all is non-null entries are equal to the row.
   */
  private def matchRow(pat: Array[Value], row: Array[Value]): Boolean = {
    var i = 0
    while (i < pat.length) {
      val pv = pat(i)
      if (pv != null)
        if (pv != row(i))
          return false
      i = i + 1
    }
    return true
  }

}
