package com.twilio.guardrail
package generators

import java.util.Locale

import _root_.io.swagger.models._
import cats.arrow.FunctionK
import cats.data.NonEmptyList
import cats.syntax.flatMap._
import com.twilio.guardrail.generators.syntax.Scala._
import com.twilio.guardrail.protocol.terms.client._
import com.twilio.guardrail.terms.RouteMeta
import com.twilio.guardrail.languages.ScalaLanguage

import scala.collection.JavaConverters._
import scala.meta._

object AkkaHttpClientGenerator {

  object ClientTermInterp extends FunctionK[ClientTerm[ScalaLanguage, ?], Target] {
    def splitOperationParts(operationId: String): (List[String], String) = {
      val parts = operationId.split('.')
      (parts.drop(1).toList, parts.last)
    }

    private[this] def toDashedCase(s: String): String = {
      val lowercased =
        "^([A-Z])".r.replaceAllIn(s, m => m.group(1).toLowerCase(Locale.US))
      "([A-Z])".r
        .replaceAllIn(lowercased, m => '-' +: m.group(1).toLowerCase(Locale.US))
    }

    private[this] def formatClientName(clientName: Option[String]): Term.Param =
      clientName.fold(
        param"clientName: String"
      )(name => param"clientName: String = ${Lit.String(toDashedCase(name))}")

    private[this] def formatHost(schemes: List[String], host: Option[String]): Term.Param =
      host
        .map {
          case v if !v.startsWith("http") =>
            val scheme = schemes.headOption.getOrElse("http")
            s"$scheme://$v"
          case v => v
        }
        .fold(param"host: String")(v => param"host: String = ${Lit.String(v)}")

    def apply[T](term: ClientTerm[ScalaLanguage, T]): Target[T] = term match {
      case GenerateClientOperation(className, route @ RouteMeta(pathStr, httpMethod, operation), methodName, tracing, parameters, responses) =>
        def generateUrlWithParams(path: String, pathArgs: List[ScalaParameter[ScalaLanguage]], qsArgs: List[ScalaParameter[ScalaLanguage]]): Target[Term] =
          for {
            _    <- Target.log.debug("generateClientOperation", "generateUrlWithParams")(s"Using $path and ${pathArgs.map(_.argName)}")
            base <- SwaggerUtil.paths.generateUrlPathParams(path, pathArgs)

            _ <- Target.log.debug("generateClientOperation", "generateUrlWithParams")(s"QS: $qsArgs")

            suffix = if (path.contains("?")) {
              Lit.String("&")
            } else {
              Lit.String("?")
            }

            _ <- Target.log.debug("generateClientOperation", "generateUrlWithParams")(s"QS: ${qsArgs}")

            result = NonEmptyList
              .fromList(qsArgs)
              .fold(base)({
                _.foldLeft[Term](q"$base + $suffix") {
                  case (a, ScalaParameter(_, _, paramName, argName, _)) =>
                    q""" $a + Formatter.addArg(${Lit
                      .String(argName.value)}, $paramName)"""
                }
              })
          } yield result

        def generateFormDataParams(parameters: List[ScalaParameter[ScalaLanguage]], needsMultipart: Boolean): Option[Term] =
          if (parameters.isEmpty) {
            None
          } else if (needsMultipart) {
            def liftOptionFileTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"$tParamName.map(v => Multipart.FormData.BodyPart(${tName.toLit}, v))"

            def liftFileTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"Some(Multipart.FormData.BodyPart(${tName.toLit}, $tParamName))"

            def liftOptionTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"$tParamName.map(v => Multipart.FormData.BodyPart(${tName.toLit}, Formatter.show(v)))"

            def liftTerm(tParamName: Term.Name, tName: RawParameterName) =
              q"Some(Multipart.FormData.BodyPart(${tName.toLit}, Formatter.show($tParamName)))"

            val args: List[Term] = parameters.foldLeft(List.empty[Term]) {
              case (a, ScalaParameter(_, param, paramName, argName, _)) =>
                val lifter: (Term.Name, RawParameterName) => Term =
                  param match {
                    case param"$_: Option[BodyPartEntity]" =>
                      liftOptionFileTerm _
                    case param"$_: Option[BodyPartEntity] = $_" =>
                      liftOptionFileTerm _
                    case param"$_: BodyPartEntity"      => liftFileTerm _
                    case param"$_: BodyPartEntity = $_" => liftFileTerm _
                    case param"$_: Option[$_]"          => liftOptionTerm _
                    case param"$_: Option[$_] = $_"     => liftOptionTerm _
                    case _                              => liftTerm _
                  }
                a :+ lifter(paramName, argName)
            }
            Some(q"List(..$args)")
          } else {
            def liftTerm(tParamName: Term, tName: RawParameterName) =
              q"List((${tName.toLit}, Formatter.show($tParamName)))"

            def liftIterable(tParamName: Term, tName: RawParameterName) =
              q"$tParamName.toList.map((${tName.toLit}, _))"

            def liftOptionTerm(tpe: Type)(tParamName: Term, tName: RawParameterName) = {
              val lifter = tpe match {
                case t"Iterable[$_]" => liftIterable _
                case _               => liftTerm _
              }
              q"${tParamName}.toList.flatMap(${Term.Block(List(q" x => ${lifter(Term.Name("x"), tName)}"))})"
            }

            val args: List[Term] = parameters.foldLeft(List.empty[Term]) {
              case (a, ScalaParameter(_, param, paramName, argName, _)) =>
                val lifter: (Term.Name, RawParameterName) => Term =
                  param match {
                    case param"$_: Option[$tpe]"      => liftOptionTerm(tpe) _
                    case param"$_: Option[$tpe] = $_" => liftOptionTerm(tpe) _
                    case _                            => liftTerm _
                  }
                a :+ lifter(paramName, argName)
            }
            Some(q"List(..$args).flatten")
          }

        def generateHeaderParams(parameters: List[ScalaParameter[ScalaLanguage]]): Term = {
          def liftOptionTerm(tParamName: Term.Name, tName: RawParameterName) =
            q"$tParamName.map(v => RawHeader(${tName.toLit}, Formatter.show(v)))"

          def liftTerm(tParamName: Term.Name, tName: RawParameterName) =
            q"Some(RawHeader(${tName.toLit}, Formatter.show($tParamName)))"

          val args: List[Term] = parameters.foldLeft(List.empty[Term]) {
            case (a, ScalaParameter(_, param, paramName, argName, _)) =>
              val lifter: (Term.Name, RawParameterName) => Term = param match {
                case param"$_: Option[$_]"      => liftOptionTerm _
                case param"$_: Option[$_] = $_" => liftOptionTerm _
                case _                          => liftTerm _
              }
              a :+ lifter(paramName, argName)
          }
          q"scala.collection.immutable.Seq[Option[HttpHeader]](..$args).flatten"
        }

        def build(methodName: String,
                  httpMethod: HttpMethod,
                  urlWithParams: Term,
                  formDataParams: Option[Term],
                  headerParams: Term,
                  responses: Responses[ScalaLanguage],
                  produces: Seq[String],
                  consumes: Seq[String],
                  tracing: Boolean)(tracingArgsPre: List[ScalaParameter[ScalaLanguage]],
                                    tracingArgsPost: List[ScalaParameter[ScalaLanguage]],
                                    pathArgs: List[ScalaParameter[ScalaLanguage]],
                                    qsArgs: List[ScalaParameter[ScalaLanguage]],
                                    formArgs: List[ScalaParameter[ScalaLanguage]],
                                    body: Option[ScalaParameter[ScalaLanguage]],
                                    headerArgs: List[ScalaParameter[ScalaLanguage]],
                                    extraImplicits: List[Term.Param]): RenderedClientOperation[ScalaLanguage] = {
          val implicitParams = Option(extraImplicits).filter(_.nonEmpty)
          val defaultHeaders = param"headers: List[HttpHeader] = Nil"
          val fallbackHttpBody: Option[(Term, Type)] =
            if (Set(HttpMethod.PUT, HttpMethod.POST) contains httpMethod)
              Some((q"HttpEntity.Empty", t"HttpEntity.Strict"))
            else None
          val textPlainBody: Option[Term] =
            if (consumes.contains("text/plain"))
              body.map(
                sp =>
                  q"TextPlain(${if (sp.required) sp.paramName
                  else q"""${sp.paramName}.getOrElse("")"""})"
              )
            else None
          val safeBody: Option[(Term, Type)] =
            body.map(sp => (sp.paramName, sp.argType)).orElse(fallbackHttpBody)

          val formEntity: Option[Term] = formDataParams.map { formDataParams =>
            if (consumes.contains("multipart/form-data")) {
              q"""Multipart.FormData(Source.fromIterator { () => $formDataParams.flatten.iterator })"""
            } else {
              q"""FormData($formDataParams: _*)"""
            }
          }

          val (tracingExpr, httpClientName) =
            if (tracing)
              (List(q"""val tracingHttpClient = traceBuilder(s"$${clientName}:$${methodName}")(httpClient)"""), q"tracingHttpClient")
            else
              (List.empty, q"httpClient")

          val headersExpr = List(q"val allHeaders = headers ++ $headerParams")

          val entity: Term = formEntity
            .orElse(textPlainBody)
            .orElse(safeBody.map(_._1))
            .getOrElse(q"HttpEntity.Empty")

          val responseCompanionTerm = Term.Name(s"${methodName.capitalize}Response")
          val cases = responses.value.map { resp =>
            val responseTerm = Term.Name(s"${resp.statusCodeName.value}")
            resp.value.fold[Case](
              p"case StatusCodes.${resp.statusCodeName} => resp.discardEntityBytes().future.map(_ => Right($responseCompanionTerm.$responseTerm))"
            ) {
              case (tpe, _) =>
                p"case StatusCodes.${resp.statusCodeName} => Unmarshal(resp.entity).to[${tpe}](${Term
                  .Name(s"$methodName${resp.statusCodeName}Decoder")}, implicitly, implicitly).map(x => Right($responseCompanionTerm.$responseTerm(x)))"
            }
          } :+ p"case _ => FastFuture.successful(Left(Right(resp)))"
          val responseTypeRef = Type.Name(s"${methodName.capitalize}Response")

          val methodBody = q"""
            {
              ..${tracingExpr};
              ..${headersExpr};
              makeRequest(
                HttpMethods.${Term.Name(httpMethod.toString.toUpperCase)},
                ${urlWithParams},
                allHeaders,
                ${entity},
                HttpProtocols.`HTTP/1.1`
              ).flatMap(req =>
                EitherT(${httpClientName}(req).flatMap(resp =>
                  ${Term.Match(q"resp.status", cases)}
                ).recover({
                  case e: Throwable =>
                    Left(Left(e))
                }))
              )
            }
            """

          val arglists: List[List[Term.Param]] = List(
            Some(
              (tracingArgsPre.map(_.param) ++ pathArgs.map(_.param) ++ qsArgs
                .map(_.param) ++ formArgs.map(_.param) ++ body
                .map(_.param) ++ headerArgs.map(_.param) ++ tracingArgsPost
                .map(_.param)) :+ defaultHeaders
            ),
            implicitParams
          ).flatten

          RenderedClientOperation[ScalaLanguage](
            q"""
              def ${Term.Name(methodName)}(...${arglists}): EitherT[Future, Either[Throwable, HttpResponse], $responseTypeRef] = $methodBody
            """,
            generateCodecs(methodName, responses, produces)
          )
        }

        for {
          // Placeholder for when more functions get logging
          _ <- Target.pure(())

          produces = Option(operation.getProduces).fold(List.empty[String])(_.asScala.toList)
          consumes = Option(operation.getConsumes).fold(List.empty[String])(_.asScala.toList)

          headerArgs = parameters.headerParams
          pathArgs   = parameters.pathParams
          qsArgs     = parameters.queryStringParams
          bodyArgs   = parameters.bodyParams
          formArgs   = parameters.formParams

          _ <- Target.log.debug("generateClientOperation")(s"pathArgs: $pathArgs")

          // Generate the url with path, query parameters
          urlWithParams <- generateUrlWithParams(pathStr, pathArgs, qsArgs)

          _ <- Target.log.debug("generateClientOperation")(s"Generated: $urlWithParams")
          // Generate FormData arguments
          formDataParams = generateFormDataParams(formArgs, consumes.contains("multipart/form-data"))
          // Generate header arguments
          headerParams = generateHeaderParams(headerArgs)

          tracingArgsPre = if (tracing)
            List(ScalaParameter.fromParam(param"traceBuilder: TraceBuilder"))
          else List.empty
          tracingArgsPost = if (tracing)
            List(ScalaParameter.fromParam(param"methodName: String = ${Lit.String(toDashedCase(methodName))}"))
          else List.empty
          extraImplicits = List.empty
          renderedClientOperation = build(methodName, httpMethod, urlWithParams, formDataParams, headerParams, responses, produces, consumes, tracing)(
            tracingArgsPre,
            tracingArgsPost,
            pathArgs,
            qsArgs,
            formArgs,
            bodyArgs,
            headerArgs,
            extraImplicits
          )
        } yield renderedClientOperation

      case GetImports(tracing) => Target.pure(List.empty)

      case GetExtraImports(tracing) => Target.pure(List.empty)

      case ClientClsArgs(tracingName, schemes, host, tracing) =>
        val ihc =
          param"implicit httpClient: HttpRequest => Future[HttpResponse]"
        val iec  = param"implicit ec: ExecutionContext"
        val imat = param"implicit mat: Materializer"
        Target.pure(
          List(List(formatHost(schemes, host)) ++ (if (tracing)
                                                     Some(formatClientName(tracingName))
                                                   else None),
               List(ihc, iec, imat))
        )

      case GenerateResponseDefinitions(operationId, responses, protocolElems) =>
        Target.pure(Http4sHelper.generateResponseDefinitions(operationId, responses, protocolElems))

      case BuildStaticDefns(clientName, tracingName, schemes, host, ctorArgs, tracing) =>
        def extraConstructors(tracingName: Option[String],
                              schemes: List[String],
                              host: Option[String],
                              tpe: Type.Name,
                              ctorCall: Term.New,
                              tracing: Boolean): List[Defn] = {
          val iec  = param"implicit ec: ExecutionContext"
          val imat = param"implicit mat: Materializer"
          val tracingParams: List[Term.Param] = if (tracing) {
            List(formatClientName(tracingName))
          } else {
            List.empty
          }

          List(
            q"""
              def httpClient(httpClient: HttpRequest => Future[HttpResponse], ${formatHost(schemes, host)}, ..$tracingParams)($iec, $imat): $tpe = $ctorCall
            """
          )
        }

        def paramsToArgs(params: List[List[Term.Param]]): List[List[Term]] =
          params
            .map({
              _.map(_.name.value)
                .map(v => Term.Assign(Term.Name(v), Term.Name(v)))
                .to[List]
            })
            .to[List]

        val ctorCall: Term.New = {
          q"""
            new ${Type.Name(clientName)}(...${paramsToArgs(ctorArgs)})
          """
        }

        val decls: List[Defn] =
          q"""def apply(...$ctorArgs): ${Type.Name(clientName)} = $ctorCall""" +:
            extraConstructors(tracingName, schemes, host, Type.Name(clientName), ctorCall, tracing)
        Target.pure(
          StaticDefns[ScalaLanguage](
            className = clientName,
            extraImports = List.empty,
            members = List.empty,
            definitions = decls,
            values = List.empty
          )
        )

      case BuildClient(clientName, tracingName, schemes, host, basePath, ctorArgs, clientCalls, supportDefinitions, tracing) =>
        val client =
          q"""
            class ${Type.Name(clientName)}(...$ctorArgs) {
              val basePath: String = ${Lit.String(basePath.getOrElse(""))}

              private[this] def makeRequest[T: ToEntityMarshaller](
                method: HttpMethod,
                uri: Uri,
                headers: scala.collection.immutable.Seq[HttpHeader],
                entity: T,
                protocol: HttpProtocol
              ): EitherT[Future, Either[Throwable, HttpResponse], HttpRequest] = {
                EitherT(
                  Marshal(entity)
                    .to[RequestEntity]
                    .map[Either[Either[Throwable, HttpResponse], HttpRequest]]({ entity =>
                      Right(HttpRequest(
                        method=method,
                        uri=uri,
                        headers=headers,
                        entity=entity,
                        protocol=protocol
                      ))
                    })
                    .recover({ case t =>
                      Left(Left(t))
                    })
                )
              }

              ..$supportDefinitions;
              ..$clientCalls;
            }
          """
        Target.pure(client)
    }

    def generateCodecs(methodName: String, responses: Responses[ScalaLanguage], produces: Seq[String]): List[Defn.Val] =
      generateDecoders(methodName, responses, produces)

    def generateDecoders(methodName: String, responses: Responses[ScalaLanguage], produces: Seq[String]): List[Defn.Val] =
      for {
        resp <- responses.value
        tpe  <- resp.value.map(_._1).toList
        (decoder, baseType) = AkkaHttpHelper.generateDecoder(tpe, produces)
      } yield q"val ${Pat.Var(Term.Name(s"$methodName${resp.statusCodeName}Decoder"))} = ${decoder}"
  }

}
