package br.com.fatec.syncro

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Date

object DueDates {
    val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(value: String): LocalDate? = runCatching { LocalDate.parse(value, formatter) }.getOrNull()

    fun isValid(value: String, today: LocalDate = LocalDate.now()): Boolean =
        parse(value)?.let { !it.isBefore(today) } ?: false

    fun isDueSoon(value: String, today: LocalDate = LocalDate.now()): Boolean =
        parse(value)?.let { date ->
            !date.isBefore(today) && date.isBefore(today.plusWeeks(1))
        } ?: false

    fun isOverdue(value: String, today: LocalDate = LocalDate.now()): Boolean =
        parse(value)?.isBefore(today) ?: false

    /** Prazos ficam no Firestore como aaaa-mm-dd, que ordena corretamente. */
    fun toIso(value: String): String =
        requireNotNull(parse(value)) { "Data inválida: $value" }.toString()

    fun fromIso(value: String?): String =
        runCatching { LocalDate.parse(value).format(formatter) }.getOrDefault("")

    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")

    /** Data e hora de registros (comentários, histórico e notificações) no fuso do aparelho. */
    fun formatDateTime(date: Date?): String =
        date?.toInstant()?.atZone(ZoneId.systemDefault())?.format(dateTimeFormatter).orEmpty()
}
