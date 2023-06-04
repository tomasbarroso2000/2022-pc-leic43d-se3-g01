package pt.isel.pc.set3.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pt.isel.pc.chat.domain.ConnectedClient
import java.util.HashSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/**
 * Represents a room, namely by containing all the clients in the room
 */
class Room(
    private val name: String,
) {

    private val lock = Mutex()
    private val connectedClients = HashSet<ConnectedClient>()

    suspend fun add(connectedClient: ConnectedClient) = lock.withLock {
        connectedClients.add(connectedClient)
    }

    suspend fun remove(connectedClient: ConnectedClient) = lock.withLock {
        connectedClients.remove(connectedClient)
    }

    suspend fun post(sender: ConnectedClient, message: String) = lock.withLock {
        connectedClients.forEach {
            if (it != sender) {
                it.send(sender, message)
            }
        }
    }

    override fun toString() = name
}