package com.segue.engine

import com.segue.core.Crypto
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** Device id derived from the identity key, so both phones compute the same id without coordination. */
object DeviceIds {
    fun idFor(publicKey: ByteArray): Int {
        val h = Crypto.sha256("SG2-id".toByteArray(), publicKey)
        val v = ByteBuffer.wrap(h, 0, 4).int and 0x7fffffff
        return if (v == 0) 1 else v
    }
}

/**
 * Durable storage. saveCoordinator must not return before the bytes are on stable storage:
 * the engine releases messages and player commands only after it returns (write-ahead).
 */
interface EngineStore {
    fun loadMembers(): List<Member>
    fun saveMembers(members: List<Member>)
    fun loadGroupKey(): ByteArray?
    fun saveGroupKey(key: ByteArray?)
    fun loadCoordinator(): ByteArray?
    fun saveCoordinator(bytes: ByteArray)
}

class MemoryStore : EngineStore {
    @Volatile private var members: List<Member> = emptyList()
    @Volatile private var groupKey: ByteArray? = null
    @Volatile private var coordinator: ByteArray? = null
    @Volatile var coordinatorWrites = 0; private set

    override fun loadMembers() = members
    override fun saveMembers(members: List<Member>) { this.members = members.toList() }
    override fun loadGroupKey() = groupKey?.copyOf()
    override fun saveGroupKey(key: ByteArray?) { groupKey = key?.copyOf() }
    override fun loadCoordinator() = coordinator?.copyOf()
    override fun saveCoordinator(bytes: ByteArray) { coordinator = bytes.copyOf(); coordinatorWrites++ }
}

/** Plain-file store (works on Android under Context.filesDir): write temp, fsync, atomic rename. */
class FileStore(private val dir: File) : EngineStore {
    init { dir.mkdirs() }

    private fun read(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()

    private fun write(name: String, bytes: ByteArray?) {
        val target = File(dir, name)
        if (bytes == null) { target.delete(); return }
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "rename failed for $name" }
        }
    }

    override fun loadMembers(): List<Member> = read("members.bin")?.let { Codec.decodeMembers(it) } ?: emptyList()
    override fun saveMembers(members: List<Member>) = write("members.bin", Codec.encodeMembers(members))
    override fun loadGroupKey(): ByteArray? = read("group.key")?.takeIf { it.size == 32 }
    override fun saveGroupKey(key: ByteArray?) = write("group.key", key)
    override fun loadCoordinator(): ByteArray? = read("coordinator.bin")
    override fun saveCoordinator(bytes: ByteArray) = write("coordinator.bin", bytes)
}
