package teamthree.clp.bot.poll

import com.bot4s.telegram.methods.SendMessage
import com.bot4s.telegram.models.{InlineKeyboardButton, InlineKeyboardMarkup}
import io.circe.generic.auto._
import io.circe.syntax._
import teamthree.clp.bot._

import scala.collection.mutable

/**
  * Базовый класс опроса
  *
  * @param author автор опроса
  * @param userStorage
  */
abstract class BasePoll(val author: BotUser, val userStorage: InMemoryUserBotStorage) {
  type Action = InputMessage => StageResult

  private[BasePoll] case class StageResult(messages: Seq[SendMessage], update: Boolean)

  private var stage = 0

  private val stages = mutable.MutableList[Action]()

  protected val participants: mutable.MutableList[BotUser] = mutable.MutableList[BotUser]()

  /**
    * Сообщения отправляемые при отмене опроса
    *
    * @return Список отправлемых сообщений
    */
  protected def onCancelPoll(): Seq[SendMessage]

  /**
    * Сообщения отправляемые при завершении опроса
    *
    * @return Список отправлемых сообщений
    */
  protected def onEndPoll(): Seq[SendMessage]

  /**
    * Добавляет следующий этап опроса
    */
  protected def onStage(action: Action): Unit = {
    stages += action
  }

  /**
    * Добавлеяет участников опроса
    *
    * @param msg Сообщение со списком логинов пользователей
    * @return Список сообщений отправлемых автору опроса
    */
  def addParticipants(msg: String): Seq[SendMessage] = {
    val users = "@[A-Za-z_]+".r.findAllIn(msg).toList

    if (users.isEmpty)
      SendMessage(author.id, "Введите хотя бы одно имя пользователя") :: Nil
    else {
      users.flatMap { u =>
        userStorage.find { user => user.username == u } match {
          case Some(user: BotUser) =>
            if (user.pollAuthor == BotUser.NOT_IN_POLL) {
              addParticipant(user)
              None
            } else {
              Some(SendMessage(author.id, s"Пользователь $u уже участвует в каком либо опросе"))
            }
          case None => Some(SendMessage(author.id, s"У пользователя $u нет диалога с ботом"))
        }
      }
    }
  }

  def addParticipant(user: BotUser): Unit = {
    participants += user
    userStorage.map(user.id) { u => u.copy(pollAuthor = author.id) }
  }

  /**
    * Отмена опроса
    *
    * @return Список отправлемых сообщений
    */
  def cancelPoll(): Seq[SendMessage] = {
    val messages = onCancelPoll()
    participants.foreach { user =>
      userStorage.map(user.id) { u => u.copy(pollAuthor = BotUser.NOT_IN_POLL) }
    }

    userStorage.map(author.id) { u => u.copy(pollAuthor = BotUser.NOT_IN_POLL) }
    messages
  }

  /**
    * Перейти к следующему этапу опроса
    *
    * @param message сообщение от пользователя
    * @return Список отправлемых сообщений
    */
  def nextStage(message: InputMessage): Seq[SendMessage] = {
    val stageResult = stages(stage)(message)

    if (stageResult.update) stage += 1

    if (stage == stages.size)
      stageResult.messages ++ onEndPoll()
    else
      stageResult.messages
  }

  /**
    * Отправить сообщения участникам
    *
    * @param fun
    * @return Список отправлемых сообщений
    */
  protected def sendToParticipants(fun: BotUser => SendMessage): Seq[SendMessage] = {
    participants.map { user => fun(user) }
  }

  /**
    * Диалог подтверждения опроса
    *
    * @return
    */
  protected def sendPoll(): SendMessage = {
    val markup = InlineKeyboardMarkup.singleRow(Seq(
      InlineKeyboardButton.callbackData("Отправить", ApplyPoll(author.id, sendPoll = true).asJson.spaces2),
      InlineKeyboardButton.callbackData("Отменить", ApplyPoll(author.id, sendPoll = false).asJson.spaces2)))

    SendMessage(author.id, "Отправить опрос?", replyMarkup = Some(markup))
  }

  def next(messages: Seq[SendMessage]): StageResult = {
    StageResult(messages, update = true)
  }

  def keep(messages: Seq[SendMessage]): StageResult = {
    StageResult(messages, update = false)
  }
}
