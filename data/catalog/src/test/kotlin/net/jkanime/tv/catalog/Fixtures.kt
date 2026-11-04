package net.jkanime.tv.catalog

internal object Fixtures {
    fun text(name: String): String =
        Fixtures::class.java.classLoader!!.getResourceAsStream("fixtures/$name")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Fixture no encontrado: fixtures/$name")
}
