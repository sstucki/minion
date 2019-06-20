package se.gu
package minion

import scala.collection.SortedSet

import scala.io.Source

import de.uka.ilkd.key.java.Services
import de.uka.ilkd.key.logic.Term
import de.uka.ilkd.key.smt.SMTSolverResult.ThreeValuedTruth
import de.uka.ilkd.key.symbolic_execution.model.ITreeSettings

final class Monitor(
  val args: Arguments,
  val solver: SmtSolver,
  val services: Services,
  val settings: ITreeSettings) {

  def monitor(
    charac: MethodCharac,
    partition: List[SortedSet[Int]]): Unit = {

    import VarTransformer._

    val tf = new TermFormater(services, settings)
    charac.print(tf, args.outputFileName)

    // Prepare a map for storing the input values parsed from the
    // traces.
    val valMap = Map(
      partition.map(ps => (ps, collection.mutable.Set[List[TraceElem]]())): _*)

    // Build the spec generators.
    val tb = tf.termBuilder
    val vt = VarTransformer(tb)
    val appSpec = charac.buildAppSpec(vt)
    val monSpec = charac.buildMonSpec(vt)

    // Create a parser for program traces based on the method
    // signature.
    val parser = new TraceParser(tb, charac.params, charac.returnType)

    // Iterate through each input file and monitor the stored traces.
    for (fileName <- args.inputFileNames) {
      val (bufferedSource, strName) =
        if (fileName == "-")
          (Source.fromInputStream(System.in), "standard input")
        else
          (Source.fromFile(fileName), "file '" + fileName + "'")

      System.err.println(s"Reading traces from $strName...")

      for (line <- bufferedSource.getLines) {

        import parser.{ tb => _, _ }

        // Parse the trace.
        parse(trace, line) match {
          case Success((as, rv), _) =>
            val asStr = tf.formatTrace(as)
            val rvStr = tf.formatTraceElem(rv)
            System.err.println(s"""Parsed trace { $asStr, $rvStr }.""")

            // Instantiate the app spec to the trace we just parsed.
            val asp = appSpec(as, rv)

            System.err.print("Checking trace consistency...")
            System.err.flush()

            // Negate the formula (we want to obtain a model/counterexample)
            val (appModel, appSmtRes) = solver.check(tb.not(asp))

            import ThreeValuedTruth._

            appSmtRes.isValid match {
              case FALSIFIABLE =>
                System.err.println(" OK.")
              case VALID       =>
                System.err.println(" failed!")
                System.err.println(
                  s"ERROR: read inconsistent trace from $strName.")
                if (!appModel.isEmpty) {
                  System.err.println(s"model: ${appModel.get}")
                }
                System.err.flush
                System.out.flush
                System.out.println("Verdict: inconsistent (I).")
                Timers.abort(1)
              case UNKNOWN     =>
                System.err.println(" unknown...")
                System.err.println(
                  s"WARNING: could not establish consistency of input trace:")
                System.err.println(line)
            }

            // Add the newly parsed and checked values to the value
            // map.
            val asArr = as.toArray
            for (positions <- partition) {
              val vs = for (i <- positions.iterator) yield asArr(i)
              valMap(positions) += vs.toList
            }

            // Iterate over all the parameter sets in the partition
            // and check for minimality w.r.t. to the input values
            // collected so far.
            for (positions <- partition) {

              val pnames =
                for (p <- positions.iterator) yield charac.paramVars(p)._1
              val pnamesStr = pnames.mkString("'", "', '", "'")
              val pvars = positions.iterator.map(charac.paramVars).toList

              // Iterate over all pairs of parameters values.
              var valsToCheck = valMap(positions).toList
              if (valsToCheck.size > 1)
                System.err.println(
                  s"Checking values for parameter(s) $pnamesStr...")
              else
                System.err.println(
                  s"Not enough values to check parameter(s) $pnamesStr.")
              while (!valsToCheck.isEmpty) {
                val v1   = valsToCheck.head
                val rest = valsToCheck.tail
                for (v2 <- rest) {

                  // Instantiate the monitor spec to the selected pair
                  // of values.
                  val vvs = v1 zip v2
                  val pv  = Map(pvars zip vvs: _*)
                  val msp = monSpec(pv)

                  val valsStr = positions.toList.map { p =>
                    val pn       = charac.paramVars(p)
                    val (v1, v2) = pv(pn)
                    val v1Str = tf.formatTraceElem(v1)
                    val v2Str = tf.formatTraceElem(v2)
                    s"${pn._1} = ($v1Str, $v2Str)"
                  }.mkString(", ")
                  System.err.print(s" $valsStr ->")
                  System.err.flush()

                  // Negate the formula (we want to obtain a model)
                  val (monModel, monSmtRes) = solver.check(tb.not(msp))

                  monSmtRes.isValid match {
                    case FALSIFIABLE =>
                      System.err.println(" OK.")
                      if (!monModel.isEmpty) {
                        System.err.println(s"model: ${monModel.get}")
                      }
                    case VALID       =>
                      System.err.println(" non-minimal!")
                      System.err.println("done.")
                      System.err.flush
                      System.out.flush
                      System.out.println(
                        s"Method '${charac.name}' non-minimal for " +
                        s"'$valsStr'")
                      System.out.println("Verdict: non-minimal (F).")
                      Timers.abort(0)
                    case UNKNOWN     =>
                      System.err.println(" unknown...")
                      System.err.println(
                        "WARNING: could not establish minimality " +
                          s"for input pairs $valsStr.")
                  }
                }
                valsToCheck = rest
              }
            }

          case Failure(msg, next) =>
            System.err.println(s"ERROR: failed to parse trace: $msg.")
            System.err.println(line)
            System.err.println(" " * (next.pos.column - 1) + "^")
            System.err.flush
            System.out.flush
            System.out.println("Verdict: parse error (P).")
            Timers.abort(1)

          case Error(msg, next) =>
            System.err.println(s"ERROR: error parsing trace: $msg.")
            System.err.println(line)
            System.err.println(" " * (next.pos.column - 1) + "^")
            System.err.flush
            System.out.flush
            System.out.println("Verdict: parse error (P).")
            Timers.abort(1)
        }
      }

      System.err.println("done.")
      System.err.flush
      System.out.flush
      System.out.println("Verdict: potentially minimal (?).")

      if (fileName != "-") bufferedSource.close()
    }
  }

  def monitorEagerly(
    charac: MethodCharac,
    partition: List[SortedSet[Int]]): Unit = {

    import VarTransformer._

    val tf = new TermFormater(services, settings)
    charac.print(tf, args.outputFileName)

    // Prepare a map for storing the input values parsed from the
    // traces.
    val valMap = Array.fill(charac.params.length)(Set[TraceElem]())

    // Build the spec generators.
    val tb = tf.termBuilder
    val vt = VarTransformer(tb)
    val appSpec = charac.buildAppSpec(vt)
    val monSpec = charac.buildMonSpec(vt)

    // Create a parser for program traces based on the method
    // signature.
    val parser = new TraceParser(tb, charac.params, charac.returnType)

    // Iterate through each input file and monitor the stored traces.
    for (fileName <- args.inputFileNames) {
      val (bufferedSource, strName) =
        if (fileName == "-")
          (Source.fromInputStream(System.in), "standard input")
        else
          (Source.fromFile(fileName), "file '" + fileName + "'")

      System.err.println(s"Monitoring eagerly.")
      System.err.println(s"Reading traces from $strName...")

      for (line <- bufferedSource.getLines) {

        import parser.{ tb => _, _ }

        // Parse the trace.
        parse(trace, line) match {
          case Success((as, rv), _) =>
            val asStr = tf.formatTrace(as)
            val rvStr = tf.formatTraceElem(rv)
            println(s"""Parsed trace { $asStr, $rvStr }.""")

            // Instantiate the app spec to the trace we just parsed.
            val asp = appSpec(as, rv)

            System.err.print("Checking trace consistency...")
            System.err.flush()

            // Negate the formula (we want to obtain a model/counterexample)
            val (appModel, appSmtRes) = solver.check(tb.not(asp))

            import ThreeValuedTruth._

            appSmtRes.isValid match {
              case FALSIFIABLE =>
                System.err.println(" OK.")
              case VALID       =>
                System.err.println(" failed!")
                System.err.println(
                  s"ERROR: read inconsistent trace from $strName.")
                if (!appModel.isEmpty) {
                  System.err.println(s"model: ${appModel.get}")
                }
                System.err.flush
                System.out.flush
                System.out.println("Verdict: inconsistent (I).")
                Timers.abort(1)
              case UNKNOWN     =>
                System.err.println(" unknown...")
                System.err.println(
                  s"WARNING: could not establish consistency of input trace:")
                System.err.println(line)
            }

            // Add the newly parsed and checked values to the value
            // map.
            for ((av, i) <- as.zipWithIndex) valMap(i) += av

            // Iterate over all the parameter sets in the partition
            // and check for minimality w.r.t. to the input values
            // collected so far.
            for (positions <- partition) {

              val pnames =
                for (p <- positions.iterator) yield charac.paramVars(p)._1
              val pnamesStr = pnames.mkString("'", "', '", "'")
              val pvars = positions.iterator.map(charac.paramVars).toList

              // Iterate over all pairs of parameters values.
              val prodIterator = new CartesianProductIterator(
                positions.iterator.map(valMap))
              val pairCount = prodIterator.sizeL
              if (pairCount > 0)
                System.err.println(
                  s"Checking values for parameter(s) $pnamesStr...")
              else
                System.err.println(
                  s"Not enough values to check parameter(s) $pnamesStr.")
              for ((v1, v2) <- prodIterator) {

                // Instantiate the monitor spec to the selected pair
                // of values.
                val vvs = v1 zip v2
                val pv  = Map(pvars zip vvs: _*)
                val msp = monSpec(pv)

                val valsStr = positions.toList.map { p =>
                  val pn       = charac.paramVars(p)
                  val (v1, v2) = pv(pn)
                  val v1Str = tf.formatTraceElem(v1)
                  val v2Str = tf.formatTraceElem(v2)
                  s"${pn._1} = ($v1Str, $v2Str)"
                }.mkString(", ")
                System.err.print(s" $valsStr ->")
                System.err.flush()

                // Negate the formula (we want to obtain a model)
                val (monModel, monSmtRes) = solver.check(tb.not(msp))

                monSmtRes.isValid match {
                  case FALSIFIABLE =>
                    System.err.println(" OK.")
                    if (!monModel.isEmpty) {
                      System.err.println(s"model: ${monModel.get}")
                    }
                  case VALID       =>
                    System.err.println(" non-minimal!")
                    System.err.println("done.")
                    System.err.flush
                    System.out.flush
                    System.out.println(
                      s"Method '${charac.name}' non-minimal for " +
                      s"'$valsStr'")
                    System.out.println("Verdict: non-minimal (F).")
                    Timers.abort(0)
                  case UNKNOWN     =>
                    System.err.println(" unknown...")
                    System.err.println(
                      "WARNING: could not establish minimality " +
                        s"for input pairs $valsStr.")
                }
              }
            }

          case Failure(msg, next) =>
            System.err.println(s"ERROR: failed to parse trace: $msg.")
            System.err.println(line)
            System.err.println(" " * (next.pos.column - 1) + "^")
            System.err.flush
            System.out.flush
            System.out.println("Verdict: parse error (P).")
            Timers.abort(1)

          case Error(msg, next) =>
            System.err.println(s"ERROR: error parsing trace: $msg.")
            System.err.println(line)
            System.err.println(" " * (next.pos.column - 1) + "^")
            System.err.flush
            System.out.flush
            System.out.println("Verdict: parse error (P).")
            Timers.abort(1)
        }
      }

      System.err.println("done.")
      System.err.flush
      System.out.flush
      System.out.println("Verdict: potentially minimal (?).")

      if (fileName != "-") bufferedSource.close()
    }
  }

  def monitorLazily(
    charac: MethodCharac,
    partition: List[SortedSet[Int]]): Unit = {

    import VarTransformer._
    import collection.mutable.{ Set => MSet, Map => MMap }

    val tf = new TermFormater(services, settings)
    charac.print(tf, args.outputFileName)

    // Prepare a map for storing the input values parsed from the
    // traces.  We will group traces according to their return values,
    // so we need a map from (sets of) of parameter positions to maps,
    // each of which associates a return value with a set of parameter
    // values.
    val valMap: Map[SortedSet[Int], MMap[TraceElem, MSet[List[TraceElem]]]] =
      Map(partition map { ps =>
        (ps, MMap[TraceElem, MSet[List[TraceElem]]]())
      }: _*)

    // Build the spec generators.
    val tb = tf.termBuilder
    val vt = VarTransformer(tb)
    val appSpec = charac.buildAppSpec(vt)
    val monSpec = charac.buildMonSpec(vt)

    // Create a parser for program traces based on the method
    // signature.
    val parser = new TraceParser(tb, charac.params, charac.returnType)

    // Iterate through each input file and monitor the stored traces.
    for (fileName <- args.inputFileNames) {
      val (bufferedSource, strName) =
        if (fileName == "-")
          (Source.fromInputStream(System.in), "standard input")
        else
          (Source.fromFile(fileName), "file '" + fileName + "'")

      System.err.println(s"Reading traces from $strName...")

      for (line <- bufferedSource.getLines) {

        import parser.{ tb => _, _ }

        // Parse the trace.
        parse(trace, line) match {
          case Success((as, rv), _) =>
            val asStr = tf.formatTrace(as)
            val rvStr = tf.formatTraceElem(rv)
            System.err.println(s"""Parsed trace { $asStr, $rvStr }.""")

            // Instantiate the app spec to the trace we just parsed.
            val asp = appSpec(as, rv)

            System.err.print("Checking trace consistency...")
            System.err.flush()

            // Negate the formula (we want to obtain a model/counterexample)
            val (appModel, appSmtRes) = solver.check(tb.not(asp))

            import ThreeValuedTruth._

            appSmtRes.isValid match {
              case FALSIFIABLE =>
                System.err.println(" OK.")
              case VALID       =>
                System.err.println(" failed!")
                System.err.println(
                  s"ERROR: read inconsistent trace from $strName.")
                if (!appModel.isEmpty) {
                  System.err.println(s"model: ${appModel.get}")
                }
                System.err.flush
                System.out.flush
                System.out.println("Verdict: inconsistent (I).")
                Timers.abort(1)
              case UNKNOWN     =>
                System.err.println(" unknown...")
                System.err.println(
                  s"WARNING: could not establish consistency of input trace:")
                System.err.println(line)
            }

            // Add the newly parsed and checked values to the value
            // map.
            val asArr = as.toArray
            for (positions <- partition) {
              val vs = for (i <- positions.iterator) yield asArr(i)
              val vm = valMap(positions)
              if (!vm.isDefinedAt(rv)) {
                vm(rv) = MSet()
              }
              vm(rv) += vs.toList
            }

            // Iterate over all the parameter sets in the partition
            // and check for minimality w.r.t. to the input values
            // collected so far.
            for (positions <- partition) {

              val pnames =
                for (p <- positions.iterator) yield charac.paramVars(p)._1
              val pnamesStr = pnames.mkString("'", "', '", "'")
              val pvars = positions.iterator.map(charac.paramVars).toList

              // Iterate over all the observed return values.
              for (rv <- valMap(positions).keys) {
                val rvStr = tf.formatTraceElem(rv)

                // Iterate over all pairs of parameters values.
                var valsToCheck = valMap(positions)(rv).toList
                if (valsToCheck.size > 1)
                  System.err.println(
                    "Checking values for parameter(s) " + pnamesStr +
                    " and return value " + rvStr + "...")
                else
                  System.err.println(
                    "Not enough values to check parameter(s) " + pnamesStr +
                    " for return value " + rvStr + ".")
                while (!valsToCheck.isEmpty) {
                  val v1   = valsToCheck.head
                  val rest = valsToCheck.tail
                  for (v2 <- rest) {

                    // Instantiate the monitor spec to the selected pair
                    // of values.
                    val vvs = v1 zip v2
                    val pv  = Map(pvars zip vvs: _*)
                    val msp = monSpec(pv)

                    val valsStr = positions.toList.map { p =>
                      val pn       = charac.paramVars(p)
                      val (v1, v2) = pv(pn)
                      val v1Str = tf.formatTraceElem(v1)
                      val v2Str = tf.formatTraceElem(v2)
                      s"${pn._1} = ($v1Str, $v2Str)"
                    }.mkString(", ")
                    System.err.print(s" $valsStr ->")
                    System.err.flush()

                    // Negate the formula (we want to obtain a model)
                    val (monModel, monSmtRes) = solver.check(tb.not(msp))

                    monSmtRes.isValid match {
                      case FALSIFIABLE =>
                        System.err.println(" OK.")
                        if (!monModel.isEmpty) {
                          System.err.println(s"model: ${monModel.get}")
                        }
                      case VALID       =>
                        System.err.println(" non-minimal!")
                        System.err.println("done.")
                        System.err.flush
                        System.out.flush
                        System.out.println(
                          s"Method '${charac.name}' non-minimal for " +
                          s"'$valsStr'")
                        System.out.println("Verdict: non-minimal (F).")
                        Timers.abort(0)
                      case UNKNOWN     =>
                        System.err.println(" unknown...")
                        System.err.println(
                          "WARNING: could not establish minimality " +
                            s"for input pairs $valsStr.")
                    }
                  }
                  valsToCheck = rest
                }
              }
            }

          case Failure(msg, next) =>
            System.err.println(s"ERROR: failed to parse trace: $msg.")
            System.err.println(line)
            System.err.println(" " * (next.pos.column - 1) + "^")
            System.err.flush
            System.out.flush
            System.out.println("Verdict: parse error (P).")
            Timers.abort(1)

          case Error(msg, next) =>
            System.err.println(s"ERROR: error parsing trace: $msg.")
            System.err.println(line)
            System.err.println(" " * (next.pos.column - 1) + "^")
            System.err.flush
            System.out.flush
            System.out.println("Verdict: parse error (P).")
            Timers.abort(1)
        }
      }

      System.err.println("done.")
      System.err.flush
      System.out.flush
      System.out.println("Verdict: potentially minimal (?).")

      if (fileName != "-") bufferedSource.close()
    }
  }
}
