package se.gu
package minion

object Timers {

  private var args: Arguments = null

  private var startTime:        Option[Long] = None
  private var symbExecStopTime: Option[Long] = None
  private var extractStopTime:  Option[Long] = None
  private var monitorStopTime:  Option[Long] = None

  def init(args: Arguments): Unit = { this.args = args }

  def start(): Unit = { startTime = Some(System.currentTimeMillis) }

  def stop(): Option[Long] = startTime.map(System.currentTimeMillis - _)

  def stopSymbExec() = { symbExecStopTime = stop() }
  def stopExtract()  = { extractStopTime = stop() }
  def stopMonitor()  = { monitorStopTime = stop() }

  def stopAll(): Unit = {
    if (symbExecStopTime.isEmpty) {
      stopSymbExec()
    } else if (extractStopTime.isEmpty) {
      stopExtract()
    } else if (monitorStopTime.isEmpty) {
      stopMonitor()
    }
  }

  def printTime(name: String, t: Option[Long]): Unit = {
    t match {
      case Some(t) => System.out.println(name + ": " + t)
      case None    =>
    }
  }

  def printTimers() = {
    if (args != null && args.printTimers) {
      System.out.flush
      System.out.println()
      System.out.println("Timers (ms):")
      printTime(" - symbolic execution ", symbExecStopTime)
      printTime(" - spec. extraction   ", extractStopTime)
      printTime(" - monitoring         ", monitorStopTime)
    }
  }

  def abort(code: Int) = {
    stopAll()
    printTimers()
    System.exit(code)
  }
}
