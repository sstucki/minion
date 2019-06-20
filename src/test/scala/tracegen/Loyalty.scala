package se.gu
package tracegen

import org.scalatest.FlatSpec

import java.io.{ File, PrintWriter }

/** Trace generator for LoyaltyApp example. */
class LoyaltyGen(val baseDir: String, val numTraces: Int) {

  val lylt = new loyalty.LoyaltyApp

  // An adaptive monolithic minimizer for 'compStatusLevel' that
  // memoizes previous traces.
  def mminCompStatusLevelThunk(): Int => Int = {

    // Map for memoizing traces.
    val traceMap = collection.mutable.Map[Int, Int]()

    (flights: Int) => {

      // Compute the result.
      val r = lylt.compStatusLevel(flights)

      // Check for previous traces associated with this fee.
      traceMap.get(r) match {
        case None =>     // new result: memoize and return trace
          traceMap += ((r, flights))
          flights
        case Some(t) =>  // known result: return memoized trace
          t
      }
    }
  }

  def genRandom(round: Int): Unit = {

    val w1 = new PrintWriter(new File(
      baseDir + s"LoyaltyApp.compStatusLevel.in.nonmin.$round.txt"))
    val w2 = new PrintWriter(new File(
      baseDir + s"LoyaltyApp.compStatusLevel.in.mono.OK.$round.txt"))

    val minCompStatusLevel = mminCompStatusLevelThunk()
    val rpg = new java.util.Random(1234 + round)

    print("Generating traces...")

    for (_ <- 0 until numTraces) {
      print(".")

      // Generate random inputs.
      val f = rpg.nextInt(101)

      // Compute the result.
      val r = lylt.compStatusLevel(f)

      // non-minimized.
      w1.println(s"$f,$r")

      // mono-minimized (same as dist-minimized in this case).
      val fMm = minCompStatusLevel(f)
      w2.println(s"$fMm,$r")
    }

    w1.close()
    w2.close()

    println(" done.")
  }
}

class LoyaltySpec extends FlatSpec {

  val rounds = 10
  val basePath = "traces/loyalty/"

  "LoyaltyGen" should s"generate $rounds rounds of random traces" in {

    // Make sure directory exists.
    new File(basePath).mkdirs()

    val tollGen = new LoyaltyGen(basePath, 100)
    for (i <- 0 until rounds) tollGen.genRandom(i)
  }
}
