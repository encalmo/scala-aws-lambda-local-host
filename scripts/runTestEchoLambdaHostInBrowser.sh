#!/bin/sh

scala run --main-class org.encalmo.lambda.host.LocalLambdaHost \
    --quiet --suppress-directives-in-multiple-files-warning \
    --suppress-outdated-dependency-warning . \
    -- \
    --mode=browser \
    --lambda-script="scala run --quiet --suppress-directives-in-multiple-files-warning --suppress-outdated-dependency-warning ./scripts/testEchoLambda.scala" \
    --lambda-name=TestEchoLambda
