import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option


class Launcher {
    @Option(name = "-p", usage = "port")
    private var port = 8888

    @Option(name = "-l", usage = "log")
    private var log = true

    private fun launch(args: Array<String>) {
        val parser = CmdLineParser(this)
        try {
            parser.parseArgument(*args)
        } catch (e: CmdLineException) {
            System.err.println(e.message)
            System.err.println("java -jar TCPServer.jar [-p port] [-l log]")
            parser.printUsage(System.err)
        }
        try {
            TCPServer(port, log)
        } catch (e: Exception) {
            System.err.println(e.message)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Launcher().launch(args)
        }
    }
}