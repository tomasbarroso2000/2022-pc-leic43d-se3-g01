package pt.isel.pc.chat.domain

/**
 * Messages returned to the client or written into the standard output.
 */
object Messages {
    const val SERVER_IS_BOUND = "Server is ready for business."
    const val SERVER_IS_ENDING = "Server is ending, bye."
    const val SERVER_SHUTDOWN = "Server is shutting down."
    const val SERVER_ACCEPTED_CLIENT = "Server accepted client."
    const val CLIENT_WELCOME = "System> Welcome!"
    const val ERR_NOT_IN_A_ROOM = "System> Error: cannot send a message while not in a room."
    const val ERR_INVALID_LINE = "System> Error: invalid line."
    const val BYE = "Bye!"
    const val LEAVE_ROOM = "left the room!"
    const val ENTER_ROOM = "entered the room!"

    fun enteredRoom(name: String) = "System> OK: entered room $name"
    fun messageFromClient(client: String, msg: String) = "$client> $msg"
}