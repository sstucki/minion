package se.gu
package tracegen

import org.scalatest.FlatSpec

import java.io.{ File, PrintWriter }

import toll.Toll

/** Trace generator for Toll example. */
class TollGen(val baseDir: String, val numTraces: Int) {

  val toll = new Toll

  // monolithic minimizer for 'rate'.
  def minRate(hour: Int, passengers: Int): (Int, Int) = {
    // hours:
    val r =
      if (hour >= 9 && hour <= 17) 9   //  - daytime
      else                         0   //  - nighttime

    // carpool rates: 20% off
    val p =
      if (passengers > 2) 3  // carpool rates
      else                1  // standard rates

    (r, p)
  }

  // monolithic for 'min' composed with 'fee'.
  def minMaxFee(x: (Int, Int), y: (Int, Int)): (Int, Int) = {
    val (h1, p1) = x
    val (h2, p2) = y
    if (toll.rate(h1, p1) > toll.rate(h2, p2)) x
    else y
  }

  // monolithic minimizer for 'feeSimple'.
  def minFeeSimple(h1: Int, h2: Int, p: Int): (Int, Int, Int) = {
    val r1 = minRate(h1, p)
    val r2 = minRate(h2, p)
    val (hm, pm) = minMaxFee(r1, r2)
    (hm, hm, pm)
  }

  // An adaptive monolithic minimizer for 'fee' that memoizes previous
  // traces.
  def minFeeThunk(): (Int, Int, Int, Int) => (Int, Int, Int, Int) = {

    // Map for memoizing traces.
    val traceMap = collection.mutable.Map[Int, (Int, Int, Int, Int)]()

    (h1: Int, h2: Int, h3: Int, p: Int) => {

      // Compute the new fee.
      val f = toll.fee(h1, h2, h3, p)

      // Check for previous traces associated with this fee.
      traceMap.get(f) match {
        case None =>     // new fee: memoize and return trace
          val t = (h1, h2, h3, p)
          traceMap += ((f, t))
          t
        case Some(t) =>  // known fee: return memoized trace
          t
      }
    }
  }

  def genRandom(round: Int): Unit = {
    val w1 = new PrintWriter(new File(
      baseDir + s"Toll.fee.in.nonmin.$round.txt"))
    val w2 = new PrintWriter(new File(
      baseDir + s"Toll.fee.in.dist.OK.$round.txt"))
    val w3 = new PrintWriter(new File(
      baseDir + s"Toll.fee.in.mono.OK.$round.txt"))
    val ws1 = new PrintWriter(new File(
      baseDir + s"Toll.feeSimple.in.nonmin.$round.txt"))
    val ws2 = new PrintWriter(new File(
      baseDir + s"Toll.feeSimple.in.dist.OK.$round.txt"))
    val ws3 = new PrintWriter(new File(
      baseDir + s"Toll.feeSimple.in.mono.OK.$round.txt"))

    val minFee = minFeeThunk()
    val rpg = new java.util.Random(1234 + round)

    print("Generating traces...")

    for (_ <- 0 until numTraces) {
      print(".")

      // Generate random passage hours and passenger counts.
      val h1 = rpg.nextInt(24)
      val h2 = (h1 + rpg.nextInt(3)) % 24
      val h3 = (h2 + rpg.nextInt(4)) % 24
      val p  = rpg.nextInt(5) + 1

      val feeS = toll.feeSimple(h1, h2, p)
      val fee  = toll.fee(h1, h2, h3, p)

      // non-minimized.
      ws1.println(s"$h1,$h2,$p,$feeS")
      w1.println(s"$h1,$h2,$h3,$p,$fee")

      // dist-minimized.
      val (h1Dm, pDm) = minRate(h1, p)
      val (h2Dm, _)   = minRate(h2, p)
      val (h3Dm, _)   = minRate(h3, p)
      ws2.println(s"$h1Dm,$h2Dm,$pDm,$feeS")
      w2.println(s"$h1Dm,$h2Dm,$h3Dm,$pDm,$fee")

      // mono-minimized.
      val (h1SMm, h2SMm, pSMm) = minFeeSimple(h1, h2, p)
      ws3.println(s"$h1SMm,$h2SMm,$pSMm,$feeS")
      val (h1Mm, h2Mm, h3Mm, pMm) = minFee(h1, h2, h3, p)
      w3.println(s"$h1Mm,$h2Mm,$h3Mm,$pMm,$fee")
    }

    w1.close()
    w2.close()
    w3.close()
    ws1.close()
    ws2.close()
    ws3.close()

    println(" done.")
  }
}

class TollSpec extends FlatSpec {

  val rounds = 10
  val basePath = "traces/toll/"

  "TollGen" should s"generate $rounds rounds of random traces" in {

    // Make sure directory exists.
    new File(basePath).mkdirs()

    val tollGen = new TollGen(basePath, 100)
    for (i <- 0 until rounds) tollGen.genRandom(i)
  }
}
