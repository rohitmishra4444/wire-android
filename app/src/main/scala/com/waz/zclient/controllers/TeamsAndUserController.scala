/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.controllers

import android.content.Context
import com.waz.ZLog._
import com.waz.model.ConversationData.ConversationType
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.utils.Callback
import com.waz.zclient.{BaseActivity, Injectable, Injector}

import scala.concurrent.Future

class TeamsAndUserController(implicit injector: Injector, context: Context, ec: EventContext) extends Injectable {
  import Threading.Implicits.Ui
  private implicit val tag: LogTag = logTagFor[TeamsAndUserController]

  val zms = inject[Signal[ZMessaging]]

  val teams = for {
    z <- zms
    teams <- z.teams.getSelfTeams.orElse(Signal(Set[TeamData]()))
    teamSigs <- Signal.sequence(teams.map(_.id).map(z.teamsStorage.signal).toSeq:_*)
  } yield teamSigs

  val self = for {
    z <- zms
    self <- z.usersStorage.signal(z.selfUserId)
  } yield self

  val currentTeamOrUser = Signal[Either[UserData, TeamData]]()

  private val selfAndTeams = for {
    self <- self
    teams <- teams
  } yield (self, teams)

  selfAndTeams{
    case (self, teams) =>
      currentTeamOrUser.mutate {
        case Left(user) => Left(user)
        case Right(team) if teams.exists(_.id == team.id) => Right(team)
        case _ => Left(self)
      }
  }

  val selfAndUnreadCount = for {
    z <- zms
    self <- self
    convs <- z.convsStorage.convsSignal
  } yield (self, convs.conversations.filter(c => !c.hidden && !c.archived && !c.muted && c.team.isEmpty).map(_.unreadCount).sum)

  val teamsAndUnreadCount = for {
    z <- zms
    teams <- teams
    convs <- z.convsStorage.convsSignal
  } yield teams.map(t => t -> convs.conversations.filter(c => !c.hidden && !c.archived && !c.muted && c.team.contains(t.id)).map(_.unreadCount).sum).toMap

  self.head.map{s => currentTeamOrUser ! Left(s)} //TODO: initial value

  //Things for java

  private var permissions = Map[TeamId, Set[TeamMemberData.Permission]]()
  private var teamConvs = Map[ConvId, TeamId]()

  (for {
    z <- zms
    teams <- teams
    memberData <- Signal.future(z.teamMemberStorage.getAll(teams.map(team => (z.selfUserId, team.id))))
  } yield memberData.flatten.map(md => (md.teamId, md.selfPermissions))).on(Threading.Ui) { data =>
    permissions = data.toMap
  }

  (for {
    z <- zms
    convs <- z.convsStorage.convsSignal
  } yield convs.conversations.map(conv => (conv.id, conv.team))).on(Threading.Ui) { data =>
    teamConvs = data.filter(_._2.nonEmpty).map(data => (data._1, data._2.get)).toMap
  }

  def isTeamSpace = currentTeamOrUser.currentValue.exists(_.isRight)

  def getCurrentUserOrTeamName: String ={
    currentTeamOrUser.currentValue.map {
      case Left(userData) => userData.displayName
      case Right(teamData) => teamData.name
      case _ => ""
    }.getOrElse("")
  }

  def hasCreateConversationPermission: Boolean = {
    currentTeamOrUser.currentValue match {
      case Some(Right(teamData)) => permissions.get(teamData.id).forall(_.contains(TeamMemberData.Permission.CreateConversation))
      case _ => true
    }
  }

  def selfPermissionsForConv(convId: ConvId): Option[Set[TeamMemberData.Permission]] = {
    teamConvs.get(convId) match {
      case Some(teamId) =>
        permissions.get(teamId).fold(Some(Set[TeamMemberData.Permission]()))(data => Some(data))
      case _ =>
        None
    }
  }

  //TODO hacky mchackerson - needed for the conversation fragment, remove ASAP
  def setIsGroupListener(id: ConvId, callback: Callback[java.lang.Boolean]): Unit =
    for {
      Some(conv) <- zms.map(_.convsStorage).head.flatMap(_.get(id))
      isGroup    <-
        if (conv.team.isEmpty) Future.successful(conv.convType == ConversationType.Group)
        else zms.map(_.membersStorage).head.flatMap(_.getByConv(conv.id)).map(_.map(_.userId).size > 2)
    } callback.callback(isGroup)

  def hasAddMemberPermission(convId: ConvId): Boolean = selfPermissionsForConv(convId).forall(_.contains(TeamMemberData.Permission.AddConversationMember))

  def hasRemoveMemberPermission(convId: ConvId): Boolean = selfPermissionsForConv(convId).forall(_.contains(TeamMemberData.Permission.RemoveConversationMember))

  def createAndOpenConversation(users: Array[UserId], requester: ConversationChangeRequester,  activity: BaseActivity): Unit = {
    val createConv = for {
      z <- zms.head
      currentTeam <- currentTeamOrUser.map(_.fold(_ => None, data => Some(data))).head
      conv <- currentTeam match {
        case None if users.length == 1 =>
          z.convsUi.getOrCreateOneToOneConversation(users.head)
        case None =>
          z.convsUi.createGroupConversation(ConvId(), users)
        case Some(teamData) =>
          z.convsUi.createGroupConversation(ConvId(), users, Some(teamData.id))
        case _ => Future.successful[ConversationData](ConversationData.Empty)
      }
    } yield conv

    createConv.map{ convData =>
      val iConv = activity.getStoreFactory.getConversationStore.getConversation(convData.id.str)
      activity.getStoreFactory.getConversationStore.setCurrentConversation(iConv, requester)
    }(Threading.Ui)
  }
}
