package io.ogdt.fusion

import com.typesafe.config.ConfigException

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import io.ogdt.fusion.external.http.actors.Server
import io.ogdt.fusion.core.fs.actors.FusionFS

object FusionApp {
    
    def main(args: Array[String]): Unit = {
        
        // val system = ActorSystem[FusionFS.Command](FusionFS(), "fusion-system")
        implicit val system = ActorSystem(Server("localhost", 8080), "FusionSystem")

        implicit val executionContext = system.executionContext
    }
}