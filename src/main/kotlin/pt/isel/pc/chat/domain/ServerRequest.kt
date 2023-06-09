package pt.isel.pc.chat.domain

sealed interface ServerRequest {
    // Command asking for server shutdown
    data class ShutdownCommand(val timeout: String) : ServerRequest

    // Command asking the server to exit
    object ExitCommand : ServerRequest

    // Invalid request
    data class InvalidRequest(val reason: String) : ServerRequest

    companion object {
        fun parse(line: String): ServerRequest {
            val parts = line.split(" ")
            return when (parts[0]) {
                "/shutdown" -> parseShutdown(parts)
                "/exit" -> parseExit(parts)
                else -> InvalidRequest("unknown command")
            }
        }

        private fun parseShutdown(parts: List<String>): ServerRequest =
            if (parts.size != 2) InvalidRequest("/shutdown command requires exactly one argument")
            else ShutdownCommand(parts[1])

        private fun parseExit(parts: List<String>): ServerRequest =
            if (parts.size != 1) InvalidRequest("/exit command does not have arguments")
            else ExitCommand
    }
}