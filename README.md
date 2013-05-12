# tshrdlu
=======

Author: **Jason Baldridge** (jasonbaldridge@gmail.com)
Author: **Simon Hafner** (hafnersimon@gmail.com)

The name "tshrdlu" comes from Twitter+[SHRDLU](http://en.wikipedia.org/wiki/SHRDLU).

The [@botty_anlp](https://twitter.com/botty_anlp) account is now doing
some tweeting of its own (by which I mean automated tweeting, based on
the code in this repository).

## Requirements

* Version 1.6 of the Java 2 SDK (http://java.sun.com)

## Setting up oauth

Write `src/main/resources/twitter4j.properties`, see
http://stackoverflow.com/questions/9362550/twitter4j-oauth-consumer-key-secret-not-set
for reference.

## Building the system from source

tshrdlu uses SBT (Simple Build Tool) with a standard directory
structure.  To build tshrdlu, type (in the `TSHRDLU_DIR` directory):

	$ ./build update compile

This will compile the source files and put them in
`./target/classes`. If this is your first time running it, you will see
messages about Scala being downloaded -- this is fine and
expected. Once that is over, the tshrdlu code will be compiled.

## Running it

Then you can create the scala model running

    $ bin/tshrdlu run tshrdlu.twitter.retweet.ScalaModel

It doesn't exit when it's finished (I didn't bother), so ^C as soon as
you read "Got model of None about Set(Scala)" or similar. This will
store the model.

Then you can run the bot with

    $ bin/tshrdlu bot

# Bugs

Don't use `sbt` (or `./build`) to run the bot. This will cause a crash
because the classloader can't find twitter4j.*.StatusJSONImpl. Blame
SBT. Works fine with `bin/tshrdlu`.
