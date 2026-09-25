package ai.loli.core.util

import java.util.UUID

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
    fun isValid(value: String?): Boolean = value != null && runCatching { UUID.fromString(value) }.isSuccess
}
