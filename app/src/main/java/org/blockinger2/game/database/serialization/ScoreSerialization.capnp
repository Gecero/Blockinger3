# To compile this Cap'n Proto Schema into Java code, you need
# to install Cap'n Proto (https://capnproto.org)
# and the Cap'n Proto Java Plugin (https://dwrensha.github.io/capnproto-java)

@0xefb39a39e5690607;
using Java = import "/capnp/java.capnp";
$Java.package("org.blockinger2.game.database.serialization");
$Java.outerClassname("ScoreSerialization");

struct Score {
  score @0: UInt64;
  playername @1 :Text;
}

struct ScoreList {
  scores @0 :List(Score);
}
