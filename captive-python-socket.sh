#!/bin/csh

#-- The directory where this script is
set sc=`dirname $0`
set h=`(cd $sc; pwd)`
source "$h/set-var-captive.sh"

$sc/python/client-socket.py localhost 7501 $sc/rules/rules-02.txt 5




