package xin.dshaa.nativeapp.remote

import org.junit.Assert.*
import org.junit.Test
import xin.dshaa.ui.shellapi.remote.RemoteChatMessage
import xin.dshaa.ui.shellapi.remote.RemoteRole
import xin.dshaa.ui.shellapi.remote.RemoteToolCard

/** Regression fixtures taken from the installed desktop timed-question protocol. */
class RemoteQuestionProtocolTest {
    @Test fun toolArgumentsUseSnakeCaseMultiSelectAndPreserveAllQuestionIds() {
        val questions = RemoteProtocol.parseQuestions("call", "ask_user_question", """{"timeout":1,"questions":[
            {"id":"first","question":"One","options":[{"label":"A"}]},
            {"id":"second","question":"Several","multi_select":true,"options":[{"label":"B"},{"label":"C"}]}
        ]}""")
        assertEquals(listOf("first", "second"), questions.map { it.id })
        assertFalse(questions[0].multiSelect); assertTrue(questions[1].multiSelect)
    }

    @Test fun pendingToolResultRemainsAnswerableButForegroundAndSettledResultsDoNot() {
        val questions = RemoteProtocol.parseQuestions("call", "ask_user_question", """{"questions":[{"id":"q","question":"Choose"}]}""")
        val tool = RemoteToolCard("call", "ask_user_question", "{}", """{"pending":true,"callId":"call","message":"still answerable"}""")
        val message = RemoteChatMessage("t:call", RemoteRole.Tool, tool = tool, questions = questions, questionCallId = "call")
        assertTrue(RemoteProtocol.questionAnswerable(message))
        assertFalse(RemoteProtocol.questionAnswerable(message.copy(answered = true)))
        assertFalse(RemoteProtocol.questionAnswerable(message.copy(tool = tool.copy(result = null))))
        for (result in listOf("""{"answers":[{"id":"q","selected":[]}]}""", "failure", """{"pending":false}""", """{"pending":true,"callId":"another"}""")) {
            assertFalse(RemoteProtocol.questionAnswerable(message.copy(tool = tool.copy(result = result))))
        }
        assertFalse(RemoteProtocol.questionAnswerable(message.copy(tool = tool.copy(isError = true))))
    }
}
