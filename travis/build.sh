#!/bin/bash
set -e

# Increase JAXP entity size limits for large p2 repository metadata
export MAVEN_OPTS="$MAVEN_OPTS -Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.maxParameterEntitySizeLimit=0"

echo "Compiling gama.annotations"
cd $( dirname $( realpath "${BASH_SOURCE[0]}" ) )/../gama.annotations
mvn clean install "$@"

echo "Compiling gama.processor"
cd ../gama.processor 
mvn clean install "$@"

echo "Compiling gama.parent"
cd ../gama.parent 
mvn clean install "$@"
