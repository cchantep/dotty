package dotty.tools
package dotc
package core

import Types._, Contexts._, Symbols._
import Decorators._
import config.Config
import config.Printers._

/** Methods for adding constraints and solving them.
 *
 * Constraints are required to be in normalized form. This means
 * (1) if P <: Q in C then also Q >: P in C
 * (2) if P r Q in C and Q r R in C then also P r R in C, where r is <: or :>
 *
 * "P <: Q in C" means here: There is a constraint P <: H[Q],
 *     where H is the multi-hole context given by:
 *
 *      H = []
 *          H & T
 *          T & H
 *          H | H
 *
 *  (the idea is that a parameter Q in a H context is guaranteed to be a supertype of P).
 *
 * "P >: Q in C" means: There is a constraint P >: L[Q],
 *     where L is the multi-hole context given by:
 *
 *      L = []
 *          L | T
 *          T | L
 *          L & L
 */
trait ConstraintHandling {
  
  implicit val ctx: Context
  
  def isSubType(tp1: Type, tp2: Type): Boolean
  
  val state: TyperState
  import state.constraint
  
  private var addConstraintInvocations = 0

  /** If the constraint is frozen we cannot add new bounds to the constraint. */
  protected var frozenConstraint = false

  private def addOneUpperBound(param: PolyParam, bound: Type): Boolean = 
    constraint.entry(param) match {
      case oldBounds @ TypeBounds(lo, hi) =>
        val newHi = hi & bound
        (newHi eq hi) || {
          val newBounds = oldBounds.derivedTypeBounds(lo, newHi)
          constraint = constraint.updateEntry(param, newBounds)
          isSubType(lo, newHi)
        }
      case _ =>
        true
    }
      
   private def addOneLowerBound(param: PolyParam, bound: Type): Boolean =
    constraint.entry(param) match {
      case oldBounds @ TypeBounds(lo, hi) =>
        val newLo = lo | bound
        (newLo eq lo) || {
          val newBounds = oldBounds.derivedTypeBounds(newLo, hi)
          constraint = constraint.updateEntry(param, newBounds)
          isSubType(newLo, hi)
        }
      case _ =>
        true
    }
   
  protected def addUpperBound(param: PolyParam, bound: Type): Boolean = {
    def description = i"constraint $param <: $bound to\n$constraint"
    if (bound.isRef(defn.NothingClass) && ctx.typerState.isGlobalCommittable) {
      def msg = s"!!! instantiated to Nothing: $param, constraint = ${constraint.show}"
      if (Config.flagInstantiationToNothing) assert(false, msg)
      else ctx.log(msg)
    }
    constr.println(i"adding $description")
    val lower = constraint.lower(param)
    val res = addOneUpperBound(param, bound) && lower.forall(addOneUpperBound(_, bound))
    constr.println(i"added $description = $res")
    res
  }
    
  protected def addLowerBound(param: PolyParam, bound: Type): Boolean = {
    def description = i"constraint $param >: $bound to\n$constraint"
    constr.println(i"adding $description")
    val upper = constraint.upper(param)
    val res = addOneLowerBound(param, bound) && upper.forall(addOneLowerBound(_, bound))
    constr.println(i"added $description = $res")
    res
  }
 
  protected def addLess(p1: PolyParam, p2: PolyParam): Boolean = {
    def description = i"ordering $p1 <: $p2 to\n$constraint"
    val res =
      if (constraint.isLess(p2, p1)) unify(p2, p1) 
        // !!! this is direction dependent - unify(p1, p2) makes i94-nada loop forever.
        //     Need to investigate why this is the case. 
        //     The symptom is that we get a subtyping constraint of the form P { ... } <: P
      else {
        val down1 = p1 :: constraint.exclusiveLower(p1, p2)
        val up2 = p2 :: constraint.exclusiveUpper(p2, p1)
        val lo1 = constraint.nonParamBounds(p1).lo
        val hi2 = constraint.nonParamBounds(p2).hi
        constr.println(i"adding $description down1 = $down1, up2 = $up2")
        constraint = constraint.addLess(p1, p2)
        down1.forall(addOneUpperBound(_, hi2)) && up2.forall(addOneLowerBound(_, lo1))
      }
    constr.println(i"added $description = $res")
    res
  }
  
  /** Make p2 = p1, transfer all bounds of p2 to p1
   *  @pre  less(p1)(p2)
   */
  private def unify(p1: PolyParam, p2: PolyParam): Boolean = {
    constr.println(s"unifying $p1 $p2")
    val down = constraint.exclusiveLower(p2, p1)
    val up = constraint.exclusiveUpper(p1, p2)
    constraint = constraint.unify(p1, p2)
    val bounds = constraint.nonParamBounds(p1)
    val lo = bounds.lo
    val hi = bounds.hi
    isSubType(lo, hi) &&
    down.forall(addOneUpperBound(_, hi)) && 
    up.forall(addOneLowerBound(_, lo))
  }
  
  protected final def isSubTypeWhenFrozen(tp1: Type, tp2: Type): Boolean = {
    val saved = frozenConstraint
    frozenConstraint = true
    try isSubType(tp1, tp2)
    finally frozenConstraint = saved
  }

  /** Test whether the lower bounds of all parameters in this
   *  constraint are a solution to the constraint.
   */
  protected final def isSatisfiable: Boolean =
    constraint.forallParams { param =>
      val TypeBounds(lo, hi) = constraint.entry(param)
      isSubType(lo, hi) || {
        ctx.log(i"sub fail $lo <:< $hi")
        false
      }
    }

  /** Solve constraint set for given type parameter `param`.
   *  If `fromBelow` is true the parameter is approximated by its lower bound,
   *  otherwise it is approximated by its upper bound. However, any occurrences
   *  of the parameter in a refinement somewhere in the bound are removed.
   *  (Such occurrences can arise for F-bounded types).
   *  The constraint is left unchanged.
   *  @return the instantiating type
   *  @pre `param` is in the constraint's domain.
   */
  final def approximation(param: PolyParam, fromBelow: Boolean): Type = {
    val avoidParam = new TypeMap {
      override def stopAtStatic = true
      def apply(tp: Type) = mapOver {
        tp match {
          case tp: RefinedType if param occursIn tp.refinedInfo => tp.parent
          case _ => tp
        }
      }
    }
    val bound = if (fromBelow) constraint.fullLowerBound(param) else constraint.fullUpperBound(param)
    val inst = avoidParam(bound)
    typr.println(s"approx ${param.show}, from below = $fromBelow, bound = ${bound.show}, inst = ${inst.show}")
    inst
  }

  /** Constraint `c1` subsumes constraint `c2`, if under `c2` as constraint we have
   *  for all poly params `p` defined in `c2` as `p >: L2 <: U2`:
   *
   *     c1 defines p with bounds p >: L1 <: U1, and
   *     L2 <: L1, and
   *     U1 <: U2
   *
   *  Both `c1` and `c2` are required to derive from constraint `pre`, possibly
   *  narrowing it with further bounds.
   */
  protected final def subsumes(c1: Constraint, c2: Constraint, pre: Constraint): Boolean =
    if (c2 eq pre) true
    else if (c1 eq pre) false
    else {
      val saved = constraint
      try
        c2.forallParams(p => 
          c1.contains(p) &&
          c2.upper(p).forall(c1.isLess(p, _)) &&
          isSubTypeWhenFrozen(c1.nonParamBounds(p), c2.nonParamBounds(p)))
      finally constraint = saved
    }
  
  protected def solvedConstraint = false

  /** The current bounds of type parameter `param` */
  final def bounds(param: PolyParam): TypeBounds = constraint.entry(param) match {
    case bounds: TypeBounds => bounds
    case _ => param.binder.paramBounds(param.paramNum)
  }
  
  def initialize(pt: PolyType): Boolean = {
    //println(i"INIT**! $pt")
    checkPropagated(i"initialized $pt") {
      pt.paramNames.indices.forall { i =>
        val param = PolyParam(pt, i)
        val bounds = constraint.nonParamBounds(param)
        val lower = constraint.lower(param)
        val upper = constraint.upper(param)
        if (lower.nonEmpty && !bounds.lo.isRef(defn.NothingClass) ||
          upper.nonEmpty && !bounds.hi.isRef(defn.AnyClass)) println(i"INIT*** $pt")
        lower.forall(addOneUpperBound(_, bounds.hi)) &&
          upper.forall(addOneLowerBound(_, bounds.lo))
      }
    }
  }

  protected def constraintImpliesSub(param: PolyParam, tp: Type): Boolean = 
    isSubTypeWhenFrozen(bounds(param).hi, tp)

  protected def constraintImpliesSuper(param: PolyParam, tp: Type): Boolean = 
    isSubTypeWhenFrozen(tp, bounds(param).lo)

  final def canConstrain(param: PolyParam): Boolean =
    !frozenConstraint && !solvedConstraint && (constraint contains param)

  protected def addConstraint(param: PolyParam, bound: Type, fromBelow: Boolean): Boolean = {
    def description = i"constr $param ${if (fromBelow) ">:" else "<:"} $bound:\n$constraint"
    checkPropagated(s"adding $description")(true)
    checkPropagated(s"added $description") {
      addConstraintInvocations += 1
      try bound.dealias.stripTypeVar match {
        case bound: PolyParam if constraint contains bound =>
          if (fromBelow) addLess(bound, param) else addLess(param, bound)
        case bound: AndOrType if fromBelow != bound.isAnd =>
          addConstraint(param, bound.tp1, fromBelow) && addConstraint(param, bound.tp2, fromBelow)
        case bound: WildcardType =>
          true
        case bound: ErrorType =>
          true
        case _ =>
          if (fromBelow) addLowerBound(param, bound)
          else addUpperBound(param, bound)
      }
      finally addConstraintInvocations -= 1
    }
  }
   
  def checkPropagated(msg: => String)(result: Boolean): Boolean = {
    if (result && addConstraintInvocations == 0) {
      frozenConstraint = true
      for (p <- constraint.domainParams) {
        for (u <- constraint.upper(p))
          assert(bounds(p).hi <:< bounds(u).hi, i"propagation failure for upper $p subsumes $u\n$msg")
        for (l <- constraint.lower(p))
          assert(bounds(l).lo <:< bounds(p).lo, i"propagation failure for lower $p subsumes $l\n$msg")
      }
      frozenConstraint = false
    }
    result
  }
}
