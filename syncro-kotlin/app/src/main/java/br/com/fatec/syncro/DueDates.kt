package br.com.fatec.syncro

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

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
}
