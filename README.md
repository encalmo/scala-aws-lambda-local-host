<a href="https://github.com/encalmo/scala-aws-lambda-local-host">![GitHub](https://img.shields.io/badge/github-%23121011.svg?style=for-the-badge&logo=github&logoColor=white)</a> <a href="https://central.sonatype.com/artifact/org.encalmo/scala-aws-lambda-local-host_3" target="_blank">![Maven Central Version](https://img.shields.io/maven-central/v/org.encalmo/scala-aws-lambda-local-host_3?style=for-the-badge)</a> <a href="https://encalmo.github.io/scala-aws-lambda-local-host/scaladoc/org/encalmo/lambda/host.html" target="_blank"><img alt="Scaladoc" src="https://img.shields.io/badge/docs-scaladoc-red?style=for-the-badge"></a>

# scala-aws-lambda-local-host

Local emulator of the AWS Lambda host environment.

Starts local HTTP server implementing https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html.

Provides browser-based HTML interface to send input to lambda and to read output.

![image](screenshot.png)
![image](screenshot2.jpg)

## Table of contents

- [Dependencies](#dependencies)
- [Usage](#usage)
- [Options](#options)
- [Example](#example)
- [Project content](#project-content)

## Dependencies

   - [Scala](https://www.scala-lang.org) >= 3.3.5
   - com.lihaoyi [**os-lib** 0.11.4](https://github.com/com-lihaoyi/os-lib)

## Usage

Use with SBT

    libraryDependencies += "org.encalmo" %% "scala-aws-lambda-local-host" % "0.9.1"

or with SCALA-CLI

    //> using dep org.encalmo::scala-aws-lambda-local-host:0.9.1

## Options

|Option|Description|
|---|---|
|--mode|User interface choice. Either `commandline` (default) or `browser`|
|--lambda-name|Name of the lambda passed to AWS_LAMBDA_FUNCTION_NAME environment variable|
|--lambda-script|Command line to run custom lambda runtime|

## Example

Run AWS Lambda host locally (works with any lambda runtime):

```
scala run --main-class org.encalmo.lambda.host.LocalLambdaHost \
    --quiet --suppress-directives-in-multiple-files-warning \
    --suppress-outdated-dependency-warning . \
    -- \
    --mode=browser
```

Run AWS Lambda host locally with some Scala lambda:

```
scala run --main-class org.encalmo.lambda.host.LocalLambdaHost \
    --quiet --suppress-directives-in-multiple-files-warning \
    --suppress-outdated-dependency-warning . \
    -- \
    --mode=browser \
    --lambda-script="scala run --quiet --suppress-directives-in-multiple-files-warning --suppress-outdated-dependency-warning --dependency=org.encalmo::scala-aws-lambda-runtime:0.9.1 --main-class org.encalmo.lambda.TestEchoLambda" \
    --lambda-name=Echo
```

Run an example TestEchoLambda:

```
/scripts/runTestEchoLambdaHostInBrowser.sh
```


## Project content

```
├── .github
│   └── workflows
│       ├── pages.yaml
│       ├── release.yaml
│       └── test.yaml
│
├── .gitignore
├── .scalafmt.conf
├── LICENSE
├── LocalLambdaHost.scala
├── project.scala
├── README.md
├── screenshot.png
├── screenshot2.jpg
└── scripts
    ├── runTestEchoLambdaHostInBrowser.sh
    └── testEchoLambda.scala
```

