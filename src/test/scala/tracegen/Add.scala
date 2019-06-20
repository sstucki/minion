package se.gu
package tracegen

import org.scalatest.FlatSpec

import java.io.{ File, PrintWriter }

import add.Add

/** Trace generator for AddApp example. */
class AddGen(val baseDir: String, val numTraces: Int) {

  val add = new Add

  // An adaptive monolithic minimizer for 'add' that memoizes previous
  // traces.
  def mminAddThunk(): (Int, Int) => (Int, Int) = {

    // Map for memoizing traces.
    val traceMap = collection.mutable.Map[Int, (Int, Int)]()

    (x: Int, y: Int) => {

      // Compute the new result.
      val r = add.add(x, y)

      // Check for previous traces associated with this result.
      traceMap.get(r) match {
        case None =>     // new result: memoize and return trace
          val t = (x, y)
          traceMap += ((r, t))
          t
        case Some(t) =>  // known result: return memoized trace
          t
      }
    }
  }

  def genRandom(round: Int): Unit = {

    val w1 = new PrintWriter(new File(
      baseDir + s"Add.add.in.nonmin.$round.txt"))
    val w2 = new PrintWriter(new File(
      baseDir + s"Add.add.in.mono.OK.$round.txt"))

    val minAdd = mminAddThunk()
    val rpg = new java.util.Random(1234 + round)

    print("Generating traces...")

    for (_ <- 0 until numTraces) {
      print(".")

      // Generate random inputs.
      val x = rpg.nextInt(8)
      val y = rpg.nextInt(8)

      // Compute the result.
      val r = add.add(x, y)

      // non-minimized (same as dist-minimized).
      w1.println(s"$x,$y,$r")

      // mono-minimized.
      val (xMm, yMm) = minAdd(x, y)
      w2.println(s"$xMm,$yMm,$r")
    }

    w1.close()
    w2.close()

    println(" done.")
  }
}

class AddSpec extends FlatSpec {

  val rounds = 10
  val basePath = "traces/add/"

  // Ignoring this generator temporarily to disable the corresponding
  // entry in the benchmarks.
  ignore /*"AddGen"*/ should s"generate $rounds rounds of random traces" in {

    // Make sure directory exists.
    new File(basePath).mkdirs()

    val tollGen = new AddGen(basePath, 100)
    for (i <- 0 until rounds) tollGen.genRandom(i)
  }
}
