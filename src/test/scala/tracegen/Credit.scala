package se.gu
package tracegen

import org.scalatest.FlatSpec

import java.io.{ File, PrintWriter }

/** Trace generator for CreditApp example. */
class CreditGen(val baseDir: String, val numTraces: Int) {

  val cred = new credit.CreditApp

  // Distributed minimizer for 'compCreditScore'.
  def dminCreditScore(incidents: Int, tax: Int): (Int, Int) = {
    // hours:
    val i =
      if      (incidents == 0) 0  // result 2 or 1
      else if (incidents == 1) 1  // result 1
      else                     2  // result 0

    val t =
      if (tax == 3) 3  // result 0, 1 or 2
      else          1  // result 0 or 1

    (i, t)
  }

  // An adaptive monolithic minimizer for 'compCreditScore' that
  // memoizes previous traces.
  def mminCompScoreThunk(): (Int, Int) => (Int, Int) = {

    // Map for memoizing traces.
    val traceMap = collection.mutable.Map[Int, (Int, Int)]()

    (incidents: Int, tax: Int) => {

      // Compute the new result.
      val r = cred.compCreditScore(incidents, tax)

      // Check for previous traces associated with this result.
      traceMap.get(r) match {
        case None =>     // new result: memoize and return trace
          val t = (incidents, tax)
          traceMap += ((r, t))
          t
        case Some(t) =>  // known result: return memoized trace
          t
      }
    }
  }

  def genRandom(round: Int): Unit = {

    val w1 = new PrintWriter(new File(
      baseDir + s"CreditApp.compCreditScore.in.nonmin.$round.txt"))
    val w2 = new PrintWriter(new File(
      baseDir + s"CreditApp.compCreditScore.in.dist.OK.$round.txt"))
    val w3 = new PrintWriter(new File(
      baseDir + s"CreditApp.compCreditScore.in.mono.OK.$round.txt"))

    val minCompCreditScore = mminCompScoreThunk()
    val rpg = new java.util.Random(1234 + round)

    print("Generating traces...")

    for (_ <- 0 until numTraces) {
      print(".")

      // Generate random inputs.
      val i = rpg.nextInt(4)
      val t = rpg.nextInt(3) + 1

      // Compute the result.
      val r = cred.compCreditScore(i, t)

      // non-minimized.
      w1.println(s"$i,$t,$r")

      // dist-minimized.
      val (iDm, tDm) = dminCreditScore(i, t)
      w2.println(s"$iDm,$tDm,$r")

      // mono-minimized.
      val (iMm, tMm) = minCompCreditScore(i, t)
      w3.println(s"$iMm,$tMm,$r")
    }

    w1.close()
    w2.close()
    w3.close()

    println(" done.")
  }
}

class CreditSpec extends FlatSpec {

  val rounds = 10
  val basePath = "traces/credit/"

  "CreditGen" should s"generate $rounds rounds of random traces" in {

    // Make sure directory exists.
    new File(basePath).mkdirs()

    val tollGen = new CreditGen(basePath, 100)
    for (i <- 0 until rounds) tollGen.genRandom(i)
  }
}
