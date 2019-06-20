package se.gu
package minion

import scala.collection.JavaConverters._
import scala.collection.SortedSet

import scala.reflect.ClassTag

final class CartesianProductIterator[A : ClassTag](
  sets: TraversableOnce[TraversableOnce[A]]) extends Iterator[(Seq[A], Seq[A])] {

  /**
   * Increment the position counter `pos` by one.
   * @param pos the counter to increment.
   * @return `true` on overflow.
   */
  def incrementPosCounter(pos: Array[Int]): Boolean = {
    var res = true
    var i = 0
    while (res && i < pos.size) {
      if (pos(i) < (seqs(i).size - 1)) {
        pos(i) += 1
        res = false
      } else {
        pos(i) = 0
        i += 1
      }
    }
    res
  }

  /**
   * Update the array `state` according to the position counter `pos`.
   * @param state the array to update.
   * @param pos the counter used to index the original collections.
   * @return the updated array.
   */
  def update(state: Array[A], pos: Array[Int]): Array[A] = {
    for (i <- 0 until pos.size) { state(i) = seqs(i)(pos(i)) }
    state
  }

  val seqs: Array[Array[A]] = sets.toArray.map(_.toArray[A])
  val pos1: Array[Int] = Array.fill(seqs.size)(0)
  val pos2: Array[Int] = {
    val res = Array.fill(seqs.size)(0)
    incrementPosCounter(res)
    res
  }

  val sizeL: Long = {
    val prodSize = seqs.iterator.map(_.size.toLong).product
    if (prodSize > 0) prodSize * (prodSize - 1) / 2 else 0
  }

  override val size: Int = sizeL.toInt

  var hasNext: Boolean = sizeL > 0

  val next1: Array[A] = if (hasNext) {
    Array.tabulate(seqs.size)(i => seqs(i)(0))
  } else null
  val next2: Array[A] = if (hasNext) {
    val res = Array.ofDim(seqs.size)
    update(res, pos2)
    res
  } else null

  def next(): (Seq[A], Seq[A]) = {
    if (hasNext) {
      // Make a copy of the current state.
      val res = (next1.clone.toSeq, next2.clone.toSeq)

      // Increment the counters
      val done2 = incrementPosCounter(pos2)
      val done1 = if (done2) {
        incrementPosCounter(pos1)
        pos1.copyToArray(pos2)
        incrementPosCounter(pos2)
      } else false
      hasNext = !done1

      // Update the state
      if (!done1) update(next1, pos1)
      if (!done2) update(next2, pos2)

      res
    } else null
  }
}
