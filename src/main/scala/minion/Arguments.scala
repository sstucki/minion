package se.gu
package minion

import java.io.File

import de.uka.ilkd.key.smt.SolverType

/** Record holding command line arguments. */
final class Arguments(
  val srcFile: File,
  val className: String,
  val eager: Boolean,
  val lazyMon: Boolean,
  val maxDepth: Int,
  val monolithic: Boolean,
  val mtdName: String,
  val printTimers: Boolean,
  val unrollLoops: Boolean,
  val solverType: SolverType,
  val outputFileName: Option[String],
  val inputFileNames: Array[String])

object Arguments {

  /**
   * Print command-line usage instructions and exit.
   * @param cmd The command that launched this program.
   * @param status The exit status.
   */
  def usage(status: Int) {
    val cmd = getClass.getPackage.getName
    val solverNames = SmtSolver.getSolverNames
    val solversStr =
      if (solverNames.isEmpty) "no solvers found!"
      else solverNames.mkString(", ")
    val st = if (status == 0) System.out else System.err
    st.println("Usage: " + cmd + " [OPTIONS] source-file [files ...]")
    st.println("Options:")
    st.println(" -h --help               print this message and exit")
    st.println(" -d --depth  DEPTH       set maximal path depth to DEPTH")
    st.println(" -e --eager              monitor eagerly")
    st.println(" -l --lazy               monitor lazily")
    st.println(" -m --method [CLS::]MTD  extract method MTD of class CLS")
    st.println("    --mono               check for monolithic minimality")
    st.println(" -o --output FILE        extract method to XML file FILE")
    st.println(" -u --unroll-loops       unroll loops (don't apply invariants)")
    st.println(" -s --solver NAME        use SMT solver NAME (" +
      solversStr + ")")
    st.println(" -t --timers             print timing info")
    System.exit(status)
  }

  def apply(args: Array[String]): Arguments = {
    import Main._

    // Parse options
    val argc = args.length
    var i: Int = 0
    var cont: Boolean = true
    var className: String = ""
    var eager: Boolean = false
    var lazyMon: Boolean = false
    var maxDepth: Int = 100
    var monolithic: Boolean = false
    var mtdName: String = ""
    var printTimers: Boolean = false
    var unrollLoops: Boolean = false
    var solverName: Option[String] = None
    var outputFileName: Option[String] = None
    while (i < argc && cont) {
      args(i) match {
        case "-d" | "--depth" =>
          i += 1
          if (i >= argc) {
            System.err.println(
              "ERROR: missing path depth after '-d' option.")
            usage(1)
          }
          maxDepth = args(i).toInt
        case "-e" | "--eager"  => eager = true
        case "-l" | "--lazy"   => lazyMon = true
        case "-m" | "--method" =>
          i += 1
          if (i >= argc) {
            System.err.println("ERROR: missing method name after '-m' option.")
            usage(1)
          }
          args(i).split("::") match {
            case Array(cn, mn) => className = cn; mtdName = mn
            case Array(n)      => mtdName   = n
          }
        case "-o" | "--output" =>
          i += 1
          if (i >= argc) {
            System.err.println(
              "ERROR: missing output file name after '-o' option.")
            usage(1)
          }
          outputFileName = Some(args(i))
        case "-u" | "--unroll-loops" => unrollLoops = true
        case "-s" | "--solver" =>
          i += 1
          if (i >= argc) {
            System.err.println(
              "ERROR: missing solver name after '-u' option.")
            usage(1)
          }
          solverName = Some(args(i))
        case "-t" | "--timers"       => printTimers = true
        case "--mono"                => monolithic = true
        case "-h" | "--help"         => usage(0)
        case "--"                    => cont = false  // last option
        case arg if arg(0) == '-'    =>
          System.err.println(s"ERROR: unrecognized option: '$arg'")
          usage(1)
        case _                       => cont = false; i -= 1
      }
      i += 1
    }

    // First non-option argument is the input file.
    if (i >= argc) {
      System.err.println("ERROR: missing input file.")
      usage(1)
    }
    val srcFile = new File(args(i))
    i += 1

    // Remaining arguments are input files.
    val inputFileNames = if (i >= argc) Array("-") else args.drop(i)

    // If the user did not specify a solver name pick a suitable
    // default solver.
    val solverType = solverName match {
      case Some(name) =>
        val stOpt = SmtSolver.getSolverType(name)
        if (stOpt.isEmpty) {
          System.err.println(
            "ERROR: Unknown or unsupported solver type: " + name)
          usage(1)
          stOpt.get  // not reachable.
        } else stOpt.get
      case None => SmtSolver.defaultSolverType
    }

    // If the user did not specify a class name, try guessing it from
    // the file name.
    if (className == "") {
      val fn = srcFile.getName
      if (fn.endsWith(".java")) className = fn.dropRight(5)
    }

    if (eager && !monolithic) {
      System.err.println(
        "WARNING: eager monitoring has no effect unless monitoring for " +
          "monolithic minimality is enabled using the '-m' option.")
    }

    new Arguments(
      srcFile,
      className,
      eager,
      lazyMon,
      maxDepth,
      monolithic,
      mtdName,
      printTimers,
      unrollLoops,
      solverType,
      outputFileName,
      inputFileNames)
  }
}
