package tshrdlu.util
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.ExtendedActorSystem
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit
 
// Loads configs from application.conf. Currently used to check if the
// retweeter should ask twitter for tweets.
class SettingsImpl(config: Config) extends Extension {
  val SetupStream: Boolean = config.getString("retweeter.setupstream") == "true"
}
object Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider {
 
  override def lookup = Settings
 
  override def createExtension(system: ExtendedActorSystem) = new SettingsImpl(system.settings.config)
}
