# tshrdlu
=======

Author: **Jason Baldridge** (jasonbaldridge@gmail.com)
Author: **Simon Hafner** (hafnersimon@gmail.com)

The name "tshrdlu" comes from Twitter+[SHRDLU](http://en.wikipedia.org/wiki/SHRDLU).

The [@botty_anlp](https://twitter.com/botty_anlp) account is now doing
some tweeting of its own (by which I mean automated tweeting, based on
the code in this repository).

## Talking to the bot

The bot should be running as @botty_anlp, feel free to talk to it.
Please keep crashes reproducible.

Commands the bot currently understands:

### Adding models

    @botty_anlp .* tweets about $keyword like $name $name $name
    @botty_anlp .* tweets like $name $name $name about $keyword 

The order doesn't matter, I didn't want to look it up every time. If
the model already exists, it will add the users you provided. Might
not give a response the second time because twitter doesn't like
posting the same status twice.

### Improving models

    @botty_anlp no .*
    @botty_anlp bad bot .*

As a reply to a tweet that you thing doesn't correspond to the model.
The `.*` so you don't have to delete your own status to avoid the same
problem as above with the same tweet twice.

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
SBT. Works fine with `bin/tshrdlu`. If you still like a repl, try

    $ bin/tshrdlu repl

Then copy/paste the following into the repl:

    val system = tshrdlu.twitter.retweet.RetweetTester.setup("twitter")

This loads the actor system of the bot and connects it to the
FilterStream from Twitter. If you don't want it to setup the
FilterStream, you can choose `commandLine` instead of `twitter`.
