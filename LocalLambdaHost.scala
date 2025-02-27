package org.encalmo.lambda.host

import com.sun.net.httpserver.{HttpContext, HttpExchange, HttpHandler, HttpServer}

import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.io.AnsiColor.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.Using.Releasable

/** An application emulating locally the AWS Lambda execution environment. */
object LocalLambdaHost {

  final def main(args: Array[String]): Unit = {

    enum Mode:
      case commandline
      case browser

    val mode: Mode =
      if (args != null && args.exists(_ == "--mode=browser"))
      then Mode.browser
      else Mode.commandline

    val lambdaScriptPrefix = "--lambda-script="

    val lambdaScript =
      Option(args)
        .flatMap(_.find(_.startsWith(lambdaScriptPrefix)))
        .map { s =>
          s.drop(lambdaScriptPrefix.length())
            .dropWhile(_ == '"')
            .reverse
            .dropWhile(_ == '"')
            .reverse
        }

    val lambdaNamePrefix = "--lambda-name="

    val lambdaName =
      Option(args)
        .flatMap(_.find(_.startsWith(lambdaNamePrefix)))
        .map { s =>
          s.drop(lambdaNamePrefix.length())
            .dropWhile(_ == '"')
            .reverse
            .dropWhile(_ == '"')
            .reverse
        }

    val inputQueue = new LinkedBlockingQueue[String](1)
    val responseQueue = new LinkedBlockingQueue[String](1)

    val mainThread = Thread.currentThread()

    given Releasable[HttpServer] with
      def release(server: HttpServer): Unit = server.stop(0)

    Using(HttpServer.create(new InetSocketAddress(9999), 0)) { server =>
      var ready = false
      val address = s"localhost:${server.getAddress().getPort()}"
      server.start()
      println(
        s"${CYAN}Started local AWS Lambda execution environment in a $mode mode at port ${YELLOW}${server
            .getAddress()
            .getPort()}${RESET}"
      )
      if (mode == Mode.commandline) {
        println(
          s"${CYAN}In a $mode mode you will be able to type your lambda input here.${RESET}"
        )
        println(
          s"${CYAN}Waiting for you to run the lambda in a separate terminal session ...${RESET}"
        )
      }

      server.createContext(
        "/2018-06-01/runtime/init/error",
        (exchange: HttpExchange) => {
          try {
            val body = Source
              .fromInputStream(exchange.getRequestBody())
              .mkString
            println(s"Initialization error:\n${RED}$body${RESET}")
            exchange.sendResponseHeaders(202, -1)
          } catch {
            case e: IOException =>
            case e              => e.printStackTrace()
          }
        }
      )

      var responseHttpContext: HttpContext = null
      var errorHttpContext: HttpContext = null

      val responseHttpHandler: HttpHandler = (exchange: HttpExchange) =>
        Thread.ofVirtual().start {
          new Runnable {
            override def run(): Unit =
              try {
                val body = Source
                  .fromInputStream(exchange.getRequestBody())
                  .mkString
                exchange.sendResponseHeaders(202, -1)
                println(s"${CYAN}Received lambda response:${RESET}")
                println(s"${GREEN}$body${RESET}")
                if (mode == Mode.browser) {
                  responseQueue.offer(body)
                }
              } catch {
                case e: IOException =>
                case e              => e.printStackTrace()
              } finally {
                server.removeContext(responseHttpContext)
                server.removeContext(errorHttpContext)
              }
          }
        }

      val errorHttpHandler: HttpHandler = (exchange: HttpExchange) =>
        Thread.ofVirtual().start {
          new Runnable {
            override def run(): Unit =
              try {
                val body = Source
                  .fromInputStream(exchange.getRequestBody())
                  .mkString
                exchange.sendResponseHeaders(202, -1)
                println(s"${CYAN}Received lambda error response:${RESET}")
                println(s"${MAGENTA}$body${RESET}")
                if (mode == Mode.browser) {
                  responseQueue.offer(body)
                }
              } catch {
                case e: IOException =>
                case e              => e.printStackTrace()
              } finally {
                server.removeContext(responseHttpContext)
                server.removeContext(errorHttpContext)
              }
          }
        }

      server.createContext(
        "/2018-06-01/runtime/invocation/next",
        (exchange: HttpExchange) =>
          if ready
          then
            try {
              val requestId = UUID.randomUUID().toString()

              val responseHeaders = exchange.getResponseHeaders()
              responseHeaders.put(
                "Lambda-Runtime-Aws-Request-Id",
                Seq(requestId).asJava
              )
              responseHeaders.put(
                "Lambda-Runtime-Deadline-Ms",
                Seq("30000").asJava
              )
              responseHeaders.put(
                "Lambda-Runtime-Invoked-Function-Arn",
                Seq(
                  "arn:aws:lambda:us-east-1:00000000:function:TestFunction"
                ).asJava
              )
              responseHeaders.put(
                "Lambda-Runtime-Trace-Id",
                Seq(
                  "Root=1-5bef4de7-ad49b0e87f6ef6c87fc2e700;Parent=9a9197af755a6419;Sampled=1"
                ).asJava
              )

              Thread.ofVirtual().start {
                new Runnable {
                  override def run(): Unit = {
                    var input: String = null
                    while (input == null) {
                      if (mode == Mode.commandline)
                      then {
                        readInputFromCommandLine().match {
                          case Some(i) if !i.isBlank() => input = i
                          case _                       => input = null
                        }
                      } else if (mode == Mode.browser) {
                        input = inputQueue.poll(1, TimeUnit.SECONDS)
                      }
                    }

                    responseHttpContext = server.createContext(
                      s"/2018-06-01/runtime/invocation/$requestId/response",
                      responseHttpHandler
                    )
                    errorHttpContext = server.createContext(
                      s"/2018-06-01/runtime/invocation/$requestId/error",
                      errorHttpHandler
                    )

                    println(s"${CYAN}Sending an event ... ${RESET}")
                    exchange.sendResponseHeaders(200, input.length())
                    val os = exchange.getResponseBody()
                    os.write(input.getBytes())
                    os.close()
                  }
                }
              }

            } catch {
              case e: IOException =>
              case e              => e.printStackTrace()
            }
          else exchange.sendResponseHeaders(500, -1)
      )

      if (mode == Mode.browser) {
        // Endpoint serving webapp html page
        server.createContext(
          "/",
          (exchange: HttpExchange) => {
            try {
              val page = startPage(lambdaName.getOrElse("your lambda"))
              exchange.getResponseHeaders().add("Content-Type", "text/html")
              exchange.sendResponseHeaders(200, page.length)
              val os = exchange.getResponseBody()
              os.write(page.getBytes())
              os.close()
            } catch {
              case e: IOException =>
              case e              => e.printStackTrace()
            }
          }
        )
        // Endpoint listening to close signal from the webapp
        server.createContext(
          "/close",
          (exchange: HttpExchange) => {
            println(
              s"${CYAN}Closed by the user.${RESET}"
            )
            exchange.sendResponseHeaders(204, -1)
            server.stop(0)
            mainThread.interrupt()
          }
        )
        // Endpoint listening to input events from the webapp
        server.createContext(
          "/event",
          (exchange: HttpExchange) => {
            val body = Source
              .fromInputStream(exchange.getRequestBody())
              .mkString
            inputQueue.offer(body)
            Thread.ofVirtual().start {
              new Runnable {
                override def run(): Unit = {
                  println(s"${CYAN}Waiting for lambda response.${RESET}")
                  val response = responseQueue.poll(30, TimeUnit.SECONDS)
                  if (response != null) {
                    exchange.sendResponseHeaders(200, response.length())
                    val os = exchange.getResponseBody()
                    os.write(response.getBytes())
                    os.close()
                  } else {
                    println(s"${RED}Request timed out.${RESET}")
                    exchange.sendResponseHeaders(408, -1)
                  }
                }
              }
            }
          }
        )

        val localUrl = s"http://localhost:${server.getAddress().getPort()}/"
        println(
          s"${CYAN}You will use a browser UI to provide lambda input and see the response at $localUrl.${RESET}"
        )
        os.proc("open", localUrl)
          .call()

      }

      val lambdaProcess = lambdaScript
        .map(script =>
          println(
            s"${CYAN}Running lambda script: $script ${RESET}"
          )
          Some(
            os
              .proc(script.split(" "))
              .spawn(
                cwd = os.pwd,
                env = Map(
                  "LAMBDA_RUNTIME_DEBUG_MODE" -> "true",
                  "LAMBDA_RUNTIME_TRACE_MODE" -> "true",
                  "AWS_LAMBDA_RUNTIME_API" -> s"localhost:${server.getAddress().getPort()}",
                  "AWS_LAMBDA_FUNCTION_NAME" -> lambdaName.getOrElse("test"),
                  "AWS_LAMBDA_FUNCTION_VERSION" -> "0"
                ),
                mergeErrIntoOut = true,
                destroyOnExit = true,
                stdout = os.Inherit,
                stderr = os.Inherit
              )
          )
        )
        .getOrElse {
          println(
            s"${CYAN}Waiting for you to run the lambda ...${RESET}"
          )
          None
        }

      ready = true
      println(s"${CYAN}Ready.${RESET}")

      try { mainThread.join() }
      catch {
        case e: InterruptedException =>
          lambdaProcess.foreach { p =>
            p.wrapped
              .children()
              .forEach { p =>
                println(s"${CYAN}Shutting down lambda, killing process ${YELLOW}${p.pid()}${RESET}")
                p.destroyForcibly()
              }
            p.wrapped.destroyForcibly()
          }
          ready = false
      }

      println(s"${CYAN}Bye!${RESET}")
    }
  }

  private def readInputFromCommandLine(): Option[String] = {
    System.out.print(s"${CYAN}Input event${BLINK}:${RESET} ")
    val input = scala.io.StdIn.readLine()
    processCommand(input)
  }

  private def processCommand(command: String): Option[String] =
    command match {
      case "exit" => {
        println(
          s"${CYAN}Terminating local AWS Lambda service.${RESET}"
        )
        System.exit(0)
        None
      }

      case name if os.exists(exampleFilePath(name)) =>
        val path = exampleFilePath(name)
        println(s"${CYAN}Reading event from file ${YELLOW}$path${RESET}")
        Some(os.read(path))

      case other =>
        Some(other)
    }

  private def exampleFilePath(name: String): os.Path =
    val relative = os.RelPath.apply(s"./test-events/$name.json")
    os.pwd / relative

  def startPage(lambdaName: String) = s"""
  <html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta http-equiv="X-UA-Compatible" content="ie=edge" />
    <title>Local AWS Lambda service emulator for $lambdaName</title>
    <style>
      body {
        padding: 1rem;
      }
      h1 {
        font-family: sans-serif;
        font-size: 2rem;
      }
      #layout {
        padding: 0 3rem;
      }
      #panels {
        display: flex;
        flex-direction: column;
        align-content: stretch;
        justify-content: space-between;
        align-items: center;
      }
      #menu {
        padding: 0.5rem 0;
        font-family: monospace;
        position: absolute;
        top: 2rem;
        right: 5rem;
      }
      #reset {
        cursor:pointer;
        color:blue;
        text-decoration:underline;
      }
      #input {
        font-family: monospace;
        text-wrap: wrap;
        padding: 1rem;
        width: 100%;
        overflow-wrap: break-word;
      }
      #response {
        font-family: monospace;
        text-wrap: wrap;
        padding: 1rem;
        width: 100%;
        overflow-wrap: break-word;
        white-space: pre-wrap;
      }
      #button-send {
        width: 100%;
        padding: 0.5rem;
        margin: 1rem;
        font-size: 1rem;
      }
      #html-response {
        display: none;
      }
    </style>
    <script>
      function initialize(event){
      }
      
      function sendInput(event) {
        let input = document.getElementById('input').value;
        if (input && input.length > 0) {
          hideIFrame();
          document.getElementById('button-send').disabled = true;
          document.getElementById('response').textContent = 'Sending ...';
          postData('/event', input).then((responseText) => {
            try {
              document.getElementById('button-send').disabled = false;
              if (responseText.trim().startsWith('{')) {
                const json = JSON.parse(responseText);
                if(json.body){
                  if(typeof json.body ==='string'){
                    if(json.body.startsWith('{')){
                      json.body = JSON.parse(json.body);
                    } else if(json.body.trim().startsWith('<!DOCTYPE html>')){
                      const parser = new DOMParser();
                      const html = parser.parseFromString(json.body, "text/html");
                      const iframe = document.getElementById('html-response');
                      iframe.contentWindow.document.open();
                      iframe.contentWindow.document.write(json.body);
                      iframe.contentWindow.document.close();
                      iframe.style.display = 'block';
                    }
                  }
                }
                document.getElementById('response').textContent = JSON.stringify(json,null,2);
              } else {
                document.getElementById('response').textContent = responseText;
              }
            } catch (error) {
              document.getElementById('response').textContent = responseText;
            }
          });
        } else {
          document.getElementById('response').textContent =
            'Empty input. Provide an event payload and try again!';
        }
      }

      async function postData(url, data) {
        // Default options are marked with *
        const response = await fetch(url, {
          method: 'POST', // *GET, POST, PUT, DELETE, etc.
          mode: 'cors', // no-cors, *cors, same-origin
          cache: 'no-cache', // *default, no-cache, reload, force-cache, only-if-cached
          credentials: 'same-origin', // include, *same-origin, omit
          headers: {
            'Content-Type': 'application/json',
            // 'Content-Type': 'application/x-www-form-urlencoded',
          },
          redirect: 'follow', // manual, *follow, error
          referrerPolicy: 'no-referrer', // no-referrer, *no-referrer-when-downgrade, origin, origin-when-cross-origin, same-origin, strict-origin, strict-origin-when-cross-origin, unsafe-url
          body: data, // body data type must match "Content-Type" header
        });
        if (response.status >= 200 && response.status < 300) {
          return response.text();
        } else if (response.status >= 400) {
          return new Promise((resolve, reject) =>
            resolve(`Failure: $${response.status} $${response.statusText}`)
          );
        }
      }

      function hideIFrame() {
        document.getElementById('html-response').style.display = 'none';
      }

      function resetLayout() {
        hideIFrame();
        document.getElementById('response').textContent = "";
        document.getElementById('input').value = "";
      }
    </script>
  </head>
  <body onload="initialize();">
    <div id="layout">
      <h1>Local AWS Lambda service emulator for $lambdaName</h1>
      <div id="menu">
        <a id="reset" onclick="resetLayout();">Reset</a>
        <span class="menu_item_separator">|</span>
        <a id="close" href="/close" onclick="window.close();">Close</a>
      </div>
      <div id="panels">
        <div id="request">
          <textarea
            id="input"
            rows="30"
            cols="200"
            placeholder="Insert here lambda event content."
          ></textarea>
        </div>
        <button id="button-send" onclick="sendInput();">
          Send this event to $lambdaName
        </button>
        <div id="response"></div>
      </div>
    </div>
    <iframe id="html-response" title="Lambda Html Response" width="100%" height="500">
</iframe>
  </body>
</html>
"""

}
