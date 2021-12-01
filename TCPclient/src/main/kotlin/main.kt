import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option
import java.net.InetAddress

class Launcher {
    @Option(name = "-a", usage = "inetAddress")
    private var inetAddress: InetAddress = InetAddress.getLocalHost()

    @Option(name = "-p", usage = "port")
    private var port = 8888

    @Option(name = "-r", usage = "readBufferSize")
    private var readBufferSize = 5 * 1024


    private fun launch(args: Array<String>) {
        val parser = CmdLineParser(this)
        try {
            parser.parseArgument(*args)
        } catch (e: CmdLineException) {
            System.err.println(e.message)
            System.err.println("java -jar TCPClient.jar [-a inetAddress] [-p port] [-r readBufferSize]")
            parser.printUsage(System.err)
        }
        try {
            TCPClient(port, inetAddress, readBufferSize)
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