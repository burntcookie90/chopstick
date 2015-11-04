package org.kotlinslack.chopstick

import com.squareup.okhttp.*
import groovy.lang.*
import org.gradle.api.*
import org.gradle.api.logging.LogLevel
import org.gradle.util.*
import java.io.*
import java.lang.reflect.Array
import java.net.*
import kotlin.test.fail

class ChopstickPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("chopsticks", ChopsticksExtension::class.java, project)
        project.afterEvaluate {
            //project.tasks.getByName("compileKotlin").dependsOn.add()

        }
    }
}

open class ChopsticksExtension(val project: Project) : Configurable<ChopsticksExtension> {
    override fun configure(cl: Closure<Any>): ChopsticksExtension {
        val chopsticksAction = ChopsticksSection(project)
        ConfigureUtil.configure(cl, chopsticksAction).execute()
        return this
    }
}

class ChopsticksSection(val project : Project, val destinationDirectory : String = "${project.buildDir.path}/generated/source/chopstick") {
    data class TransferItem(val path : String, val destinationDirectory : String, val isLocal : Boolean = false)
    val items = arrayListOf<TransferItem>()
    val customDirs = arrayListOf<ChopsticksSection>()

    fun destinationDir(path : String, configure : Closure<*>) {
        if(path.isNotEmpty()){
            val dir = ChopsticksSection(project, path)
            customDirs.add(dir)
            project.configure(dir, configure)
        }
    }

    fun local(path : String) {
        when {
            path.isEmpty() -> throw IllegalArgumentException("Can't have an empty path!")
            else -> items.add(TransferItem(path, destinationDirectory, true))
        }
    }

    fun github(src: String) {
        val parts = src.split(':')
        when {
            parts.size < 2 -> throw IllegalArgumentException("GitHub identifier should include repo, path and an optional hash")
            parts.size == 3 -> url("https://raw.githubusercontent.com/${parts[0]}/${parts[2]}/${parts[1]}")
            else -> url("https://raw.githubusercontent.com/${parts[0]}/master/${parts[1]}")
        }
    }

    fun url(src: Any?) {
        val source: Any? = if (src is Closure<*>) src.call() else src
        when {
            source is CharSequence -> items.add(TransferItem(source.toString(), destinationDirectory))
            source is URL -> items.add(TransferItem(source.toExternalForm(), destinationDirectory))
            source is Collection<*> -> for (sco in source) url(sco)
            source != null && source.javaClass.isArray -> {
                val len = Array.getLength(source)
                for (i in 0..len - 1) {
                    url(Array.get(source, i))
                }
            }
            else -> throw IllegalArgumentException("URL must either be a URL, a CharSequence, a Collection or an array.")
        }
    }

    fun execute() {
        val client = OkHttpClient()
        items.forEach {
            when {
                it.isLocal -> processLocal()(it)
                else -> processRemote(client)(it)
            }
        }
        customDirs.forEach {
            it.execute()
        }
    }

    private fun processRemote(client : OkHttpClient) : (TransferItem) -> Unit = {
        val (url, dest) = it
        val fullUrl = url;
        val path = File(URL(url).path)
        val destinationDir = File(dest)
        destinationDir.mkdirs()

        val file = destinationDir.resolve(path.name)
        println(fullUrl)
        client.execute(fullUrl) {
            success { response ->
                response.body().byteStream().copyTo(file.outputStream())
            }
            fail { request, ioException ->
                println("Failed: $ioException")
            }
        }
    }

    private fun processLocal() : (TransferItem) -> Unit = {
        val (path, dest) = it
        val sourceFile = File(path)
        val destDir = File(dest)
        destDir.mkdirs()
        val destFile = destDir.resolve(sourceFile.name)
        sourceFile.copyTo(destFile, true)
    }

}
