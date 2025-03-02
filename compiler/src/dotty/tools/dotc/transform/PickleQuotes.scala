package dotty.tools.dotc
package transform

import core._
import Decorators._
import Flags._
import Types._
import Contexts._
import Symbols._
import Constants._
import ast.Trees._
import ast.untpd
import ast.TreeTypeMap
import SymUtils._
import NameKinds._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.config.ScalaRelease.*

import scala.collection.mutable
import dotty.tools.dotc.core.Annotations._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.quoted._
import dotty.tools.dotc.inlines.Inlines

import scala.annotation.constructorOnly

/** Translates quoted terms and types to `unpickleExprV2` or `unpickleType` method calls.
 *
 *  Transforms top level quote
 *   ```
 *   '<a,b>{ ...
 *      val x1: U1 = ???
 *      val x2: U2 = ???
 *      ...
 *      {{{ 3 | x1 | contents0 | T0 }}} // hole
 *      ...
 *      {{{ 4 | x2 | contents1 | T1 }}} // hole
 *      ...
 *      {{{ 5 | x1, x2 | contents2 | T2 }}} // hole
 *      ...
 *    }
 *    ```
 *  to
 *    ```
 *     unpickleExprV2(
 *       pickled = [[ // PICKLED TASTY
 *         @TypeSplice type A // with bounds that do not contain captured types
 *         @TypeSplice type B // with bounds that do not contain captured types
 *         val x1 = ???
 *         val x2 = ???
 *         ...
 *         {{{ 0 | x1 | | T0 }}} // hole
 *         ...
 *         {{{ 1 | x2 | | T1 }}} // hole
 *         ...
 *         {{{ 2 | x1, x2 | | T2 }}} // hole
 *         ...
 *       ]],
 *       typeHole = Seq(a, b),
 *       termHole = (idx: Int, args: List[Any], quotes: Quotes) => idx match {
 *         case 3 => content0.apply(args(0).asInstanceOf[Expr[U1]]).apply(quotes) // beta reduced
 *         case 4 => content1.apply(args(0).asInstanceOf[Expr[U2]]).apply(quotes) // beta reduced
 *         case 5 => content2.apply(args(0).asInstanceOf[Expr[U1]], args(1).asInstanceOf[Expr[U2]]).apply(quotes) // beta reduced
 *       },
 *     )
 *    ```
 *  and then performs the same transformation on any quote contained in the `content`s.
 *
 */
class PickleQuotes extends MacroTransform {
  import PickleQuotes._
  import tpd._

  override def phaseName: String = PickleQuotes.name

  override def description: String = PickleQuotes.description

  override def allowsImplicitSearch: Boolean = true

  override def checkPostCondition(tree: Tree)(using Context): Unit =
    tree match
      case tree: Quote =>
        assert(Inlines.inInlineMethod)
      case tree: Splice =>
        assert(Inlines.inInlineMethod)
      case _ =>

  override def run(using Context): Unit =
    if (ctx.compilationUnit.needsStaging) super.run

  protected def newTransformer(using Context): Transformer = new Transformer {
    override def transform(tree: tpd.Tree)(using Context): tpd.Tree =
      tree match
        case Apply(Select(quote: Quote, nme.apply), List(quotes)) =>
          val (contents, quote1) = makeHoles(quote)
          val quote2 = encodeTypeArgs(quote1)
          val contents1 = contents ::: quote.tags
          val pickled = PickleQuotes.pickle(quote2, quotes, contents1)
          transform(pickled) // pickle quotes that are in the contents
        case tree: DefDef if !tree.rhs.isEmpty && tree.symbol.isInlineMethod =>
          tree
        case _ =>
          super.transform(tree)
  }

  private def makeHoles(quote: tpd.Quote)(using Context): (List[Tree], tpd.Quote) =
    class HoleContentExtractor extends Transformer:
      private val contents = List.newBuilder[Tree]
      override def transform(tree: tpd.Tree)(using Context): tpd.Tree =
        tree match
          case tree @ Hole(isTerm, _, _, content) =>
            assert(isTerm)
            assert(!content.isEmpty)
            contents += content
            val holeType = getTermHoleType(tree.tpe)
            val hole = untpd.cpy.Hole(tree)(content = EmptyTree).withType(holeType)
            cpy.Inlined(tree)(EmptyTree, Nil, hole)
          case tree: DefTree =>
            val newAnnotations = tree.symbol.annotations.mapconserve { annot =>
              annot.derivedAnnotation(transform(annot.tree)(using ctx.withOwner(tree.symbol)))
            }
            tree.symbol.annotations = newAnnotations
            super.transform(tree)
          case _ =>
            super.transform(tree).withType(mapAnnots(tree.tpe))

      private def mapAnnots = new TypeMap { // TODO factor out duplicated logic in Splicing
        override def apply(tp: Type): Type = {
            tp match
              case tp @ AnnotatedType(underlying, annot) =>
                val underlying1 = this(underlying)
                derivedAnnotatedType(tp, underlying1, annot.derivedAnnotation(transform(annot.tree)))
              case _ => mapOver(tp)
        }
      }

      /** Remove references to local types that will not be defined in this quote */
      private def getTermHoleType(using Context) = new TypeMap() {
        override def apply(tp: Type): Type = tp match
          case tp @ TypeRef(NoPrefix, _) =>
            // reference to term with a type defined in outer quote
            getTypeHoleType(tp)
          case tp @ TermRef(NoPrefix, _) =>
            // widen term refs to terms defined in outer quote
            apply(tp.widenTermRefExpr)
          case tp =>
            mapOver(tp)
      }

      /** Get the contents of the transformed tree */
      def getContents() =
        val res = contents.result
        contents.clear()
        res
    end HoleContentExtractor

    val holeMaker = new HoleContentExtractor
    val body1 = holeMaker.transform(quote.body)
    val quote1 = cpy.Quote(quote)(body1, quote.tags)

    (holeMaker.getContents(), quote1)
  end makeHoles

  /** Encode quote tags as holes in the quote body.
   *
   *  ```scala
   *    '<t, u>{ ... t.Underlying ... u.Underlying ... }
   *  ```
   *  becomes
   * ```scala
   *  '<t, u>{
   *    type T = {{ 0 | .. | .. | .. }}
   *    type U = {{ 1 | .. | .. | .. }}
   *    ... T ... U ...
   *  }
   * ```
   */
  private def encodeTypeArgs(quote: tpd.Quote)(using Context): tpd.Quote =
    if quote.tags.isEmpty then quote
    else
      val tdefs = quote.tags.zipWithIndex.map(mkTagSymbolAndAssignType)
      val typeMapping = quote.tags.map(_.tpe).zip(tdefs.map(_.symbol.typeRef)).toMap
      val typeMap = new TypeMap {
        override def apply(tp: Type): Type = tp match
          case TypeRef(tag: TermRef, _) if tp.typeSymbol == defn.QuotedType_splice =>
            typeMapping.getOrElse(tag, tp)
          case _ => mapOver(tp)
      }
      def treeMap(tree: Tree): Tree = tree match
          case Select(qual, _) if tree.symbol == defn.QuotedType_splice =>
            typeMapping.get(qual.tpe) match
              case Some(tag) => TypeTree(tag).withSpan(tree.span)
              case None => tree
          case _ => tree
      val body1 = new TreeTypeMap(typeMap, treeMap).transform(quote.body)
      cpy.Quote(quote)(Block(tdefs, body1), quote.tags)

  private def mkTagSymbolAndAssignType(typeArg: Tree, idx: Int)(using Context): TypeDef = {
    val holeType = getTypeHoleType(typeArg.tpe.select(tpnme.Underlying))
    val hole = untpd.cpy.Hole(typeArg)(isTerm = false, idx, Nil, EmptyTree).withType(holeType)
    val local = newSymbol(
      owner = ctx.owner,
      name = UniqueName.fresh(hole.tpe.dealias.typeSymbol.name.toTypeName),
      flags = Synthetic,
      info = TypeAlias(typeArg.tpe.select(tpnme.Underlying)),
      coord = typeArg.span
    ).asType
    local.addAnnotation(Annotation(defn.QuotedRuntime_SplicedTypeAnnot, typeArg.span))
    ctx.typeAssigner.assignType(untpd.TypeDef(local.name, hole), local).withSpan(typeArg.span)
  }

  /** Remove references to local types that will not be defined in this quote */
  private def getTypeHoleType(using Context) = new TypeMap() {
    override def apply(tp: Type): Type = tp match
      case tp: TypeRef if tp.typeSymbol.isTypeSplice =>
        apply(tp.dealias)
      case tp @ TypeRef(pre, _) if isLocalPath(pre) =>
        val hiBound = tp.typeSymbol.info match
          case info: ClassInfo => info.parents.reduce(_ & _)
          case info => info.hiBound
        apply(hiBound)
      case tp =>
        mapOver(tp)

    private def isLocalPath(tp: Type): Boolean = tp match
      case NoPrefix => true
      case tp: TermRef if !tp.symbol.is(Package) => isLocalPath(tp.prefix)
      case tp => false
  }

}

object PickleQuotes {
  import tpd._

  val name: String = "pickleQuotes"
  val description: String = "turn quoted trees into explicit run-time data structures"

  def pickle(quote: Quote, quotes: Tree, contents: List[Tree])(using Context) = {
    val body = quote.body
    val bodyType = quote.bodyType

    /** Helper methods to construct trees calling methods in `Quotes.reflect` based on the current `quotes` tree */
    object reflect extends ReifiedReflect {
      val quotesTree = quotes
    }

    /** Encode quote using Reflection.Literal
      *
      *  Generate the code
      *  ```scala
      *    quotes => quotes.reflect.TreeMethods.asExpr(
      *      quotes.reflect.Literal.apply(x$1.reflect.Constant.<typeName>.apply(<literalValue>))
      *    ).asInstanceOf[scala.quoted.Expr[<body.type>]]
      *  ```
      *  this closure is always applied directly to the actual context and the BetaReduce phase removes it.
      */
    def pickleAsLiteral(lit: Literal) = {
      val typeName = body.tpe.typeSymbol.name
      val literalValue =
        if lit.const.tag == Constants.NullTag || lit.const.tag == Constants.UnitTag then Nil
        else List(body)
      val constModule = lit.const.tag match
        case Constants.BooleanTag => defn. Quotes_reflect_BooleanConstant
        case Constants.ByteTag => defn. Quotes_reflect_ByteConstant
        case Constants.ShortTag => defn. Quotes_reflect_ShortConstant
        case Constants.IntTag => defn. Quotes_reflect_IntConstant
        case Constants.LongTag => defn. Quotes_reflect_LongConstant
        case Constants.FloatTag => defn. Quotes_reflect_FloatConstant
        case Constants.DoubleTag => defn. Quotes_reflect_DoubleConstant
        case Constants.CharTag => defn. Quotes_reflect_CharConstant
        case Constants.StringTag => defn. Quotes_reflect_StringConstant
        case Constants.UnitTag => defn. Quotes_reflect_UnitConstant
        case Constants.NullTag => defn. Quotes_reflect_NullConstant
        case Constants.ClazzTag => defn. Quotes_reflect_ClassOfConstant
      reflect.asExpr(body.tpe) {
        reflect.Literal {
          reflect.self
            .select(constModule)
            .select(nme.apply)
            .appliedToTermArgs(literalValue)
        }
      }
    }

    /** Encode quote using Reflection.Literal
      *
      *  Generate the code
      *  ```scala
      *    quotes => scala.quoted.ToExpr.{BooleanToExpr,ShortToExpr, ...}.apply(<literalValue>)(quotes)
      *  ```
      *  this closure is always applied directly to the actual context and the BetaReduce phase removes it.
      */
    def liftedValue(lit: Literal, lifter: Symbol) =
      val exprType = defn.QuotedExprClass.typeRef.appliedTo(body.tpe)
      ref(lifter).appliedToType(bodyType).select(nme.apply).appliedTo(lit).appliedTo(quotes)

    def pickleAsValue(lit: Literal) = {
      // TODO should all constants be pickled as Literals?
      // Should examine the generated bytecode size to decide and performance
      lit.const.tag match {
        case Constants.NullTag => pickleAsLiteral(lit)
        case Constants.UnitTag => pickleAsLiteral(lit)
        case Constants.BooleanTag => liftedValue(lit, defn.ToExprModule_BooleanToExpr)
        case Constants.ByteTag => liftedValue(lit, defn.ToExprModule_ByteToExpr)
        case Constants.ShortTag => liftedValue(lit, defn.ToExprModule_ShortToExpr)
        case Constants.IntTag => liftedValue(lit, defn.ToExprModule_IntToExpr)
        case Constants.LongTag => liftedValue(lit, defn.ToExprModule_LongToExpr)
        case Constants.FloatTag => liftedValue(lit, defn.ToExprModule_FloatToExpr)
        case Constants.DoubleTag => liftedValue(lit, defn.ToExprModule_DoubleToExpr)
        case Constants.CharTag => liftedValue(lit, defn.ToExprModule_CharToExpr)
        case Constants.StringTag => liftedValue(lit, defn.ToExprModule_StringToExpr)
      }
    }

    /** Encode quote using QuoteUnpickler.{unpickleExpr, unpickleType}
      *
      *  Generate the code
      *  ```scala
      *    quotes => quotes.asInstanceOf[QuoteUnpickler].<unpickleExpr|unpickleType>[<type>](
      *      <pickledQuote>,
      *      <typeHole>,
      *      <termHole>,
      *    )
      *  ```
      *  this closure is always applied directly to the actual context and the BetaReduce phase removes it.
      */
    def pickleAsTasty() = {
      val body1 =
        if body.isType then body
        else Inlined(Inlines.inlineCallTrace(ctx.owner, quote.sourcePos), Nil, body)
      val pickleQuote = PickledQuotes.pickleQuote(body1)
      val pickledQuoteStrings = pickleQuote match
        case x :: Nil => Literal(Constant(x))
        case xs => tpd.mkList(xs.map(x => Literal(Constant(x))), TypeTree(defn.StringType))

      // TODO split holes earlier into types and terms. This all holes in each category can have consecutive indices
      val (typeSplices, termSplices) = contents.zipWithIndex.partition {
        _._1.tpe.derivesFrom(defn.QuotedTypeClass)
      }

      // This and all closures in typeSplices are removed by the BetaReduce phase
      val types =
        if typeSplices.isEmpty then Literal(Constant(null)) // keep pickled quote without contents as small as possible
        else SeqLiteral(typeSplices.map(_._1), TypeTree(defn.QuotedTypeClass.typeRef.appliedTo(WildcardType)))

      // This and all closures in termSplices are removed by the BetaReduce phase
      val termHoles =
        if termSplices.isEmpty then Literal(Constant(null)) // keep pickled quote without contents as small as possible
        else
          Lambda(
            MethodType(
              List(nme.idx, nme.contents, nme.quotes).map(name => UniqueName.fresh(name).toTermName),
              List(defn.IntType, defn.SeqType.appliedTo(defn.AnyType), defn.QuotesClass.typeRef),
              defn.QuotedExprClass.typeRef.appliedTo(defn.AnyType)),
            args =>
              val cases = termSplices.map { case (splice, idx) =>
                val defn.FunctionOf(argTypes, defn.FunctionOf(quotesType :: _, _, _), _) = splice.tpe: @unchecked
                val rhs = {
                  val spliceArgs = argTypes.zipWithIndex.map { (argType, i) =>
                    args(1).select(nme.apply).appliedTo(Literal(Constant(i))).asInstance(argType)
                  }
                  val Block(List(ddef: DefDef), _) = splice: @unchecked
                  // TODO: beta reduce inner closure? Or wait until BetaReduce phase?
                  BetaReduce(
                    splice
                      .select(nme.apply).appliedToArgs(spliceArgs))
                      .select(nme.apply).appliedTo(args(2).asInstance(quotesType))
                }
                CaseDef(Literal(Constant(idx)), EmptyTree, rhs)
              }
              cases match
                case CaseDef(_, _, rhs) :: Nil => rhs
                case _ => Match(args(0).annotated(New(ref(defn.UncheckedAnnot.typeRef))), cases)
          )

      val quoteClass = if quote.isTypeQuote then defn.QuotedTypeClass else defn.QuotedExprClass
      val quotedType = quoteClass.typeRef.appliedTo(bodyType)
      val lambdaTpe = MethodType(defn.QuotesClass.typeRef :: Nil, quotedType)
      val unpickleMeth =
        if quote.isTypeQuote then defn.QuoteUnpickler_unpickleTypeV2
        else defn.QuoteUnpickler_unpickleExprV2
      val unpickleArgs =
        if quote.isTypeQuote then List(pickledQuoteStrings, types)
        else List(pickledQuoteStrings, types, termHoles)
      quotes
        .asInstance(defn.QuoteUnpicklerClass.typeRef)
        .select(unpickleMeth).appliedToType(bodyType)
        .appliedToArgs(unpickleArgs).withSpan(body.span)
    }

    /** Encode quote using Reflection.TypeRepr.typeConstructorOf
      *
      *  Generate the code
      *  ```scala
      *    quotes.reflect.TypeReprMethods.asType(
      *      quotes.reflect.TypeRepr.typeConstructorOf(classOf[<type>]])
      *    ).asInstanceOf[scala.quoted.Type[<type>]]
      *  ```
      *  this closure is always applied directly to the actual context and the BetaReduce phase removes it.
      */
    def taggedType() =
      reflect.asType(body.tpe) {
        reflect.TypeRepr_typeConstructorOf(
          TypeApply(ref(defn.Predef_classOf.termRef), body :: Nil)
        )
      }

    def getLiteral(tree: tpd.Tree): Option[Literal] = tree match
      case tree: Literal => Some(tree)
      case Block(Nil, e) => getLiteral(e)
      case Inlined(_, Nil, e) => getLiteral(e)
      case _ => None

    if body.isType then
      if contents.isEmpty && body.symbol.isPrimitiveValueClass then taggedType()
      else pickleAsTasty()
    else
      getLiteral(body) match
        case Some(lit) => pickleAsValue(lit)
        case _ => pickleAsTasty()
  }

}
