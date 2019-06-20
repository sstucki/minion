package se.gu
package minion

import scala.collection.JavaConverters._
import scala.xml._

import java.io.File
import java.{ util => ju }

import de.uka.ilkd.key.java.{ Services, SourceElement }
import de.uka.ilkd.key.java.abstraction.{ KeYJavaType, ArrayType }
import de.uka.ilkd.key.java.declaration.ParameterDeclaration

import de.uka.ilkd.key.logic.{ ProgramElementName, Term }
import de.uka.ilkd.key.logic.op.{
  IProgramMethod, IProgramVariable, Junctor, LocationVariable, LogicVariable,
  QuantifiableVariable }

import de.uka.ilkd.key.symbolic_execution.model.{
  IExecutionMethodReturn, IExecutionNode, IExecutionTermination,
  IExecutionVariable }
import de.uka.ilkd.key.symbolic_execution.util.SymbolicExecutionUtil

import VarTransformer.GenVar


/** A single, terminating path of a program characterization. */
sealed trait ProgPath {

  /** The path condition */
  def cond: Term

  /**
   * The final state of all the method variables on this path (before
   * method return)
   */
  def state: Map[GenVar, List[Term]]

  import VarTransformer._

  def buildStateTerm(vt: VarTransformer): Term = {

    // A list of disjuctions of equations relating the state variables
    // to their possible values.
    val varTerms = for ((gv, us) <- state.toSeq) yield {
      val eqs = us map { u =>
        val qv = vt.dict.get(gv) match {
          case Some(qv) => qv
          case None     =>
            val qv = genToLogVar(gv)
            vt.dict += ((gv, qv))
            qv
        }
        val varTerm = vt.tf.createTerm(qv)
        vt.tb.equals(varTerm, vt.transform(u))
      }
      vt.tb.or(eqs: _*)
    }

    vt.tb.and(varTerms: _*)
  }

  def buildPathTerm(vt: VarTransformer): Term = {
    val ct = vt.transform(cond)
    val st = buildStateTerm(vt)
    vt.tb.and(ct, st)
  }
}

/**
 * A single, terminating path of a program characterization.
 *
 * @param cond the path condition
 * @param state the final state of all the method variables on this
 *   path (before method return)
 * @param retVar the name of the return variable of the method on this
 *   path
 * @param termKind the termination kind of this path (e.g. an
 *   exception might have been thrown)
 */
final case class FullPath(
  cond: Term,
  state: Map[GenVar, List[Term]],
  retVar: GenVar,
  termKind: IExecutionTermination.TerminationKind) extends ProgPath

/**
 * A single, cut-off path of a program characterization.
 *
 * Cut-off paths are produced when symbolic execution reaches the
 * maximum path depth.
 *
 * @param cond the path condition
 * @param state the final state of all the method variables on this
 */
final case class CutOffPath(
  cond: Term,
  state: Map[GenVar, List[Term]]) extends ProgPath

object ProgPath {

  /**
   * Compute the state map of a given symbolic execution tree node.
   *
   * NOTE: we rename all the state variables, adding the STATE_PREFIX,
   * in order to distinguish them from the initial state.  E.g. if the
   * a method has a parameter "a", there will be two state variables:
   *
   *   1. a variable "a" representing the value of "a" with which the
   *      method was called, and
   *
   *   2. a variable "_$a" representing the value of "a" in the given
   *      execution node, i.e. after modifications.
   */
  def stateMap[A <: SourceElement](
    method: IProgramMethod,
    node: IExecutionNode[A]): Map[GenVar, List[Term]] = {

    val services  = node.getServices
    val tb        = services.getTermBuilder
    val classType = method.getContainerType
    val stateVars = node.getVariables

    // Filter out the self reference and exception variables.
    val selfVar = tb.selfVar(classType, false)
    val excVar  = tb.excVar(method, false)
    val filteredVars = stateVars.filter { v =>
      val vn = v.getProgramVariable.name()
      vn != selfVar.name && vn != excVar.name
    }

    import collection.mutable.{ ArrayBuffer, HashMap }

    // Build a list of pairs relating the state variables to their
    // possible values.  Recursively traverse reference values
    // (objects, arrays) to collect their child variables (as they are
    // also part of the state).
    var toCheck = filteredVars.toBuffer
    val checked    = new HashMap[StateLocation, GenVar]
    val statePairs = new ArrayBuffer[(GenVar, List[Term])]
    while (toCheck.nonEmpty) {
      val newToCheck = new ArrayBuffer[IExecutionVariable]
      val newPairs   = new ArrayBuffer[(GenVar, List[Term])]
      for (iVar <- toCheck) {
        val lOpt = StateLocation(iVar, services)
        if (lOpt.nonEmpty && !checked.contains(lOpt.get)) {
          val l = lOpt.get
          val iVals = iVar.getValues.toList
          val valTerms = for (iVal <- iVals) yield {
            if (iVal.isValueAnObject) newToCheck ++= iVal.getChildVariables
            iVal.getValue
          }
          if (iVals.nonEmpty) {
            val sort = valTerms(0).sort
            val lName = new ProgramElementName(StateLocation.toVarName(l))
            val lVar  = (lName, sort)
            checked += ((l, lVar))
            newPairs += ((lVar, valTerms))
          }
        }
      }
      toCheck = newToCheck
      statePairs ++= newPairs
    }

    statePairs.toMap
  }

  /**
   * Extract a program path from a method return node.
   *
   * @param retNode the method return node from which to extract the
   *   path state
   * @param termNode the termination node from which to extract the
   *   path's termination kind.
   */
  def apply(
    retNode: IExecutionMethodReturn,
    termNode: IExecutionTermination): ProgPath = {

    // Get the path condition
    val cond  = retNode.getPathCondition

    // Build a list of pairs relating the state variables to their
    // possible values.
    val sm = stateMap(retNode.getMethodCall.getProgramMethod, retNode)

    // Find the return variable.
    val mtdBdyStmt = retNode.getMethodCall.getActiveStatement
    val retVar     = mtdBdyStmt.getResultVariable
    assert(retVar != null)

    // Compute the list of possible return values.
    //
    // NOTE: since the return variable is a piece of state at the end
    // of the execution path, we give it the state prefix.
    val retVarNameStr = StateLocation.STATE_PREFIX + retVar.name.toString
    val retVarName    = new ProgramElementName(retVarNameStr)
    val retVals = retNode.getReturnValues.toList.map(_.getReturnValue)
    val retGVar = (retVarName, retVar.sort)
    val retPair = (retGVar, retVals)
    val state   = sm.toMap + retPair

    // Extract the termination kind.
    val termKind = termNode.getTerminationKind

    FullPath(cond, state, retGVar, termKind)
  }

  /**
   * Extract a cut-off program path from an arbitrary execution node.
   *
   * @param retNode the method return node from which to extract the
   *   path state
   * @param termNode the termination node from which to extract the
   *   path's termination kind.
   */
  def apply[A <: SourceElement](
    method: IProgramMethod, node: IExecutionNode[A]): ProgPath = {
    CutOffPath(node.getPathCondition, stateMap(method, node))
  }
}

/**
 * A program/method characterization in FOL.
 *
 * @param className the name of the enclosing class
 * @param name the method name
 * @param params the method parameters
 * @param returnType the method return type
 * @param preCond the declared precondition of the method
 * @param paths characterizations of the method paths
 */
final case class MethodCharac(
  val className: String,
  val name: String,
  val params: List[ParameterDeclaration],
  val returnType: KeYJavaType,
  val returnVarBase: String,
  val returnVars: List[GenVar],
  val preCond: Term,
  val paths: List[ProgPath]) {

  import VarTransformer._

  // /**
  //  * Parameter variables.
  //  *
  //  * These include variables that have the same prefix as a parameter.
  //  */
  // val paramVars: List[GenVar] = {
  //   val pvs = params map { p =>
  //     val pv = p.getVariableSpecification.getProgramVariable
  //     (pv.name, pv.sort)
  //   }
  //   val pvSet = pvs.toSet

  //   val pnSet = for ((n, s) <- pvSet) yield n.toString
  //   val paramStateVars = for {
  //     p <- paths
  //     ((n, s), _) <- p.state
  //     pre = n.toString.split('$')(0)
  //     if pre.nonEmpty && pnSet.contains(pre)
  //   } yield (n, s)

  //   (pvSet ++ paramStateVars).toList
  // }

  /** Parameter variables. */
  val paramVars: List[GenVar] =
    params map { p =>
      val pv = p.getVariableSpecification.getProgramVariable
      (pv.name, pv.sort)
    }

  def signature: String = {
    val paramTypes = params.map(_.getTypeReference.getName)
    name + paramTypes.mkString("(", ",", ")")
  }

  def buildFullSpec(vt: VarTransformer): Term = {

    // Transform the pre-condition and extract its free variables.
    val preCond2 = vt.transform(preCond)

    // Transform the paths and extract their free variables.
    val pathSpecs = paths.map(_.buildPathTerm(vt))

    vt.tb.and(preCond2, vt.tb.or(pathSpecs: _*))
  }

  def buildBodySpec(vt: VarTransformer): Term = {
    // Build the full spec and extract all the variables.
    val t  = buildFullSpec(vt)
    val vs = vt.dict

    // Remove the method parameters and return variables to obtain the
    // variables that are free in the method body.
    val fvs = (vs -- returnVars) -- paramVars
    vt.dict = vt.dict -- fvs.keys

    // Existentially quantify over all the free variables in the body
    // of the method characterization.
    vt.tb.ex(fvs.values.asJava, t)
  }

  /**
   * A type designating the left-hand side (LHS) of an equation.
   *
   * The LHS of an equation is either
   *
   *  - a variable name (String), or
   *  - a term.
   */
  type EqLhs = Either[String, Term]

  /**
   * Build logic formulas relating an LHS to a trace element.
   *
   * By partially applying this method, one obtains an "equation
   * builder" that can be used to generate formulas for different
   * (LHS, RHS) pairs.  The symbol table `vt` will be shared among
   * different instances.
   *
   * NOTE: if the LHS is a variable, then the individual components of
   * the RHS (trace element) will be assigned to different, dedicated
   * variables with a different variable name postfix distinguishing
   * each component of the RHS.
   *
   * E.g. assuming a `boolean[] a = { true, false }`
   *
   *   a$length = 2       // a.length
   *   a$arr$0  = true    // a[0]
   *   a$arr$1  = false   // a[1]
   *
   * If the LHS is a term, then the
   * individual components of the RHS will be assigned to different
   * composite terms with field and array selections mirroring the
   * structure of the RHS.  E.g. for `a = { true, false }`
   *
   *   a.length = 1
   *   a[0]     = true
   *   a[1]     = false
   *
   * @param vt the variable transformer/symbol table.
   * @return a function taking a pair (LHS, RHS) and returning the
   *   desired equation as a logical formula.
   */
  def buildEquations(vt: VarTransformer): (EqLhs, TraceElem) => Term = {

    val tb   = vt.tb
    val dict = vt.dict map { case ((vn, vs), qv) => (vn.toString, qv) }

    def eq(p: EqLhs, t: Term): Term = p match {
      case Left(prefix) => dict.get(prefix) match {
        case Some(v) => tb.equals(tb.`var`(v), t)
        case None    => tb.tt
      }
      case Right(t1) => tb.equals(t1, t)
    }

    def arraySel(p: EqLhs, i: Int): EqLhs = p match {
      case Left(prefix) => Left(prefix + "$arr$" + i)
      case Right(t)     => Right(tb.dotArr(t, tb.zTerm(i)))
    }

    def arrayLen(p: EqLhs): EqLhs = p match {
      case Left(prefix) => Left(prefix + "$dot$length")
      case Right(t)     => Right(tb.dotLength(t))
    }

    def buildEqs(dict: Map[String, QuantifiableVariable])(
      p: EqLhs, te: TraceElem): Term =
      te match {
        case BaseTm(t) => eq(p, t)
        case ArrayTm(ts) =>
          val selEqs = ts.zipWithIndex.map { case (t, i) =>
            buildEqs(dict)(arraySel(p, i), t)
          }
          val lenEq = buildEqs(dict)(arrayLen(p), BaseTm(tb.zTerm(ts.length)))
          tb.and(tb.and(selEqs: _*), lenEq)
      }

    buildEqs(dict)
  }

  /**
   * By partially applying this method, one obtains an "application
   * spec generator" that can be used to generate specs for different
   * sets of input arguments.  The core spec will only be generated
   * once and subsequently be shared among different instances.
   */
  def buildAppSpec(vt: VarTransformer): (List[TraceElem], TraceElem) => Term = {

    // Build the body spec and extract the variable dictionary.
    val bs   = buildBodySpec(vt)
    val dict = vt.dict
    val tb   = vt.tb
    val buildEqs = buildEquations(vt)

    // // Pre-compute the list of parameter names.
    // val paramNames =
    //   for (p <- params; v <- p.getVariables.asScala) yield v.getName
    // Pre-compute the list of parameter terms.

    { (args: List[TraceElem], retVal: TraceElem) =>

      // Build a list of equations associating the formal to the actual
      // parameters.
      assert(paramVars.length == args.length)
      val paramEqs = for ((fp, ap) <- paramVars zip args) yield {
        buildEqs(Right(tb.`var`(dict(fp))), ap)
      }

      // Build an equations associating the return variables to their
      // expected values.
      val retEqs = buildEqs(Left(returnVarBase), retVal)

      // Combine the body spec and the equations in a big conjuction.
      val eqs = retEqs :: paramEqs
      tb.and(bs, tb.and(eqs: _*))
    }
  }

  /**
   * By partially applying this method, one obtains a "monitor spec
   * generator" that can be used to generate specs for different sets
   * of input arguments.  The core spec will only be generated once
   * and subsequently be shared among different instances.
   */
  def buildMonSpec(
    vt: VarTransformer): Map[GenVar, (TraceElem, TraceElem)] => Term = {

    // Build the body spec and extract the variable dictionary.
    val bs   = buildBodySpec(vt)
    val dict = vt.dict
    val tb   = vt.tb
    val buildEqs = buildEquations(vt)

    { (args: Map[GenVar, (TraceElem, TraceElem)]) =>

      // Build a list of equations associating the formal to the actual
      // parameters.
      val paramEqs = for ((fp, (ap1, ap2)) <- args.toList) yield {
        val qvt = tb.`var`(dict(fp))
        (buildEqs(Right(qvt), ap1), buildEqs(Right(qvt), ap2))
      }
      val (pEqs1, pEqs2) = paramEqs.unzip

      // Introduce pairs of return 'constsants' and build corresponding
      // equations associating the return variables to these constants.
      val (retEqs, rest) = (for (rv <- returnVars) yield {
        val (rn, rs) = rv
        val rqv = dict(rv)
        val rvt = tb.`var`(rqv)
        val rc1 = new LogicVariable(
          new ProgramElementName(tb.newName(rn + "_1")), rs)
        val rc2 = new LogicVariable(
          new ProgramElementName(tb.newName(rn + "_2")), rs)
        val rct1 = tb.`var`(rc1)
        val rct2 = tb.`var`(rc2)
        val retEq1 = tb.equals(rvt, rct1)
        val retEq2 = tb.equals(rvt, rct2)
        val retInEq = tb.not(tb.equals(rct1, rct2))
        ((retEq1, retEq2), (retInEq, rqv))
      }).unzip
      val (retEqs1, retEqs2) = retEqs.unzip
      val (retInEqs, rqvs)   = rest.unzip

      // Create two copies of the body spec, conjoin the fixed arguments
      // and return type with their defining equations and existentially
      // bind them in their respective spec.
      val bvs = args.keys.map(dict)
      val eqs1 = tb.and(tb.and(pEqs1: _*), tb.and(retEqs1: _*))
      val eqs2 = tb.and(tb.and(pEqs2: _*), tb.and(retEqs2: _*))
      val bs1  = tb.ex(bvs.asJava, tb.and(eqs1, bs))
      val bs2  = tb.ex(bvs.asJava, tb.and(eqs2, bs))
      val bsr1 = tb.ex(rqvs.asJava, bs1)
      val bsr2 = tb.ex(rqvs.asJava, bs2)

      tb.and(bsr1, bsr2, tb.or(retInEqs: _*))
    }
  }

  /**
   * Prints the logical characterization of a given method.
   *
   * @param tf `TermFormater` for pretty printing.
   * @param outputFilename the file to print to (or `None` for stdout).
   */
  def print(tf: TermFormater, outputFileName: Option[String]) {
    val vt = VarTransformer(tf.termBuilder)
    val xmlPaths = for (p <- paths) yield {
      val condStr  = " " + tf.formatTerm(p.cond) + " "
      val stateStr = " " + tf.formatTerm(p.buildStateTerm(vt)) + " "
      val termStr  = p match {
        case FullPath(_, _, _, tk) => tk.name
        case CutOffPath(_, _)      => "CUTOFF"
      }
      <executionPath pathCondition={ condStr } state={ stateStr }
                     terminationKind={ termStr } />
    }

    val sigStr     = signature
    val preCondStr = " " + tf.formatTerm(preCond) + " "
    val xmlResult  =
      <result>
        <proof contractText={ preCondStr } type={ className }
          target={ sigStr }>
          { xmlPaths }
        </proof>
      </result>
    val pp = new scala.xml.PrettyPrinter(256, 4)

    outputFileName match {
      case None =>
        System.out.println(pp.format(xmlResult))
      case Some(f) =>
        scala.xml.XML.save(f, xmlResult, "UTF-8", true)
        System.out.println(
          "Wrote characterization of method '" +
            className + "::" + sigStr + "' to '" + f + "'")
    }
  }
}

object MethodCharac {

  def apply(
    method: IProgramMethod,
    services: Services,
    paths: List[ProgPath]): MethodCharac = {

    // Extract the class name and method signature.
    val classType     = method.getContainerType
    val className     = classType.getName
    val methodDecl    = method.getMethodDeclaration
    val methodName    = methodDecl.getName
    val methodParams  = methodDecl.getParameters.asScala.toList
    val methodRetType = method.getReturnType

    // Extract the precondition from the specification and format it.
    val preCond = getMethodPrecondition(services, method)

    // Check if one of the parameters has array type, and if so, emit
    // a warning.
    val arrayParam = methodParams.find { p =>
      p.getTypeReference.getKeYJavaType.getJavaType.isInstanceOf[ArrayType]
    }
    if (arrayParam.nonEmpty) {
      val p   = arrayParam.get
      val pn  = p.getVariableSpecification.getName
      val pjt = p.getTypeReference.getKeYJavaType.getJavaType
      val pat = pjt.asInstanceOf[ArrayType]
      val patStr = pat.getAlternativeNameRepresentation
      System.err.println(
        s"WARNING: The parameter '$pn' is of array type '$patStr'. " +
          "Parameters of array type are not supported.")
    }

    // There ought to be at least one path.
    assert(paths.size > 0)

    // Try to identify the return variable for this method.  If there
    // is none (e.g. because all the paths have been cut off) invent
    // one (and make sure to give it the STATE_PREFIX).
    val retVarNameOpt = paths.collectFirst {
      case FullPath(_, _, rv, _) => rv._1.toString
    }
    val retVarBaseName = retVarNameOpt.getOrElse {
      System.err.println(
        "WARNING: All paths cut-off, monitoring will be trivial...")
      val tb = services.getTermBuilder
      val rv = tb.resultVar(method, true)
      StateLocation.STATE_PREFIX + rv.name.toString
    }

    // Extract the return variables, i.e. all the state variables that
    // have the return base name as a prefix of their name.
    val retVars = for {
      p <- paths
      (n, s) <- p.state.keys
      if n.toString.startsWith(retVarBaseName)
    } yield (n, s)

    new MethodCharac(
      className,
      methodName,
      methodParams,
      methodRetType,
      retVarBaseName,
      retVars,
      preCond,
      paths)
  }

  /**
   * Get the precondition part of a method specification.
   *
   * @param env The KeY environment in which the precondition is
   *   defined.
   * @param pm The method of which to get the precondition.
   */
  def getMethodPrecondition(services: Services, pm: IProgramMethod): Term = {

    // Get the enclosing class of the method.
    val classType = pm.getContainerType

    // Get the method contract(s).
    val specRepo  = services.getSpecificationRepository
    val contracts = specRepo.getOperationContracts(classType, pm)

    val tb = services.getTermBuilder

    // If there are no contracts, the precond is trivial.
    if (contracts.isEmpty) tb.tt else {

      // If there are multiple contracts, combine them into one.
      val contract  = specRepo.combineOperationContracts(contracts)

      // Get the "address"/"name" of the heap.
      val heap = services.getTypeConverter.getHeapLDT.getHeap

      // Extract the precondition from the contracts.
      val ovs = contract.getOrigVars()
        val preCond =
          contract.getPre(heap, ovs.self, ovs.params, ovs.atPres, services)

      // Among the sub-terms of the precondition, there is a call to the
      // class invariant, i.e. "self.<inv>".  Try removing that term.
      //
      // FIXME: this is a rather ad-hoc way of removing the class
      // invariant. There if probably a more proper way to do such
      // things using the KeY API directly.
      val javaInfo = services.getJavaInfo
      val invOp    = javaInfo.getInv
      def removeClassInv(t: Term): Term = {
        // Only recurse into subterms if the top-level operation is a
        // conjuction. Otherwise, treat the term as an 'atom'.
        if (t.op == Junctor.AND) {
          val t1 = removeClassInv(t.sub(0))
          val t2 = removeClassInv(t.sub(1))
          tb.and(t1, t2)
        } else if (t.op == invOp)
          tb.tt
        else t
      }
      removeClassInv(preCond)
    }
  }
}
