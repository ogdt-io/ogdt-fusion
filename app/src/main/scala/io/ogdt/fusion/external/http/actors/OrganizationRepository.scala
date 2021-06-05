package io.ogdt.fusion.external.http.actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{Behavior, ActorRef}

object OrganizationRepository {
    sealed trait Status
    object Successful extends Status
    object Failed extends Status 

    import io.ogdt.fusion.external.http.entities.Organization
    sealed trait Response 
    case object OK extends Response
    final case class KO(reason: String) extends Response

    sealed trait Command
    final case class AddOrganization(organization: Organization, replyTo: ActorRef[Response]) extends Command
    final case class GetOrganizationById(id: String, replyTo: ActorRef[Response]) extends Command
    final case class UpdateOrganization(id: String, replyTo: ActorRef[Response]) extends Command
    final case class DeleteOrganization(id: String, replyTo: ActorRef[Response]) extends Command

    def apply(): Behavior[Command] = Behaviors.receiveMessage {
        case AddOrganization(organization, replyTo) =>
            replyTo ! OK
            Behaviors.same
        case GetOrganizationById(id, replyTo) =>
            replyTo ! OK
            Behaviors.same
        case UpdateOrganization(id, replyTo) =>
            replyTo ! OK
            Behaviors.same
        case DeleteOrganization(id, replyTo) =>
            replyTo ! OK
            Behaviors.same
    }

}