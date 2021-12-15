import java.lang.Thread.sleep
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.text.SimpleDateFormat
import java.util.*

var readBufferSize = 128 * 1024

data class Msg(
    val command: Command,
    val username: String,
    val usernameB: List<Byte>,
    val time: Long,
    val timeB: List<Byte>,
    val data: String,
    val dataB: List<Byte>
)

enum class Command(val value: Int) {
    SEND_MSG(0),
    SEND_FILE(1),
    AUTH(2),
    CLOSE(3)
}

/** Получение сообщения от указанного сокета.
 * Выбрасывает SocketException, если не удалось считать из socket (т.е. он закрыт).**/
fun getMsg(socket: SocketChannel): Msg {
    while (true) {
        /** Чтение первых 18 байтов **/
        val buffer = ByteBuffer.allocate(18)
        buffer.clear()
        val read = socket.read(buffer)
//        println("read $read bytes")
        if (read == 0) { // Пока ничего нет
            sleep(500)
            continue
        }
        if (read == -1)
            throw SocketException("Exception when receiving package.")
        val bytes = buffer.array()

        /** 1 байт: command **/
        val command = when (bytes[0].toUByte().toInt()) {   // Получаем код команды
            0 -> Command.SEND_MSG
            1 -> Command.SEND_FILE
            2 -> Command.AUTH
            else -> Command.CLOSE
        }

        /** 2-4 байты: dataLen **/
        var dataLen = bytes[1].toUByte().toInt()
        for (i in 2..3)
            dataLen = (dataLen shl 8).or(bytes[i].toUByte().toInt())

        /** 5-14 байты: username **/
        val usernameB = bytes.slice(4..13)
        val username = usernameB.dropWhile { it == 0.toByte() }.toByteArray().toString(Charsets.UTF_8)

        /** 15-18 байты: time **/
        val timeB = bytes.slice(14..17)
        var timeI = timeB[0].toUByte().toInt()
        for (i in 1..3)
            timeI = (timeI shl 8).or(timeB[i].toUByte().toInt())

        /** 19 и следующие dataLen байты: data **/
        val dataB = mutableListOf<Byte>()
        var readied = 0
        buffer.flip()

        while (readied != dataLen) {
            val residue = dataLen - readied
            val needReed = if (residue < readBufferSize) residue else readBufferSize

            val bufferData = ByteBuffer.allocate(needReed)
            val readData = socket.read(bufferData)

            if (readData == -1)
                throw SocketException("Exception when receiving package.")
            var bytesData = bufferData.array()

            if (readData < needReed)
                bytesData = bytesData.dropLast(needReed - readData).toByteArray()
            readied += bytesData.size
            dataB.addAll(bytesData.toList())
            bufferData.flip()
        }

        val data = if (command == Command.SEND_FILE)
            ""
        else
            dataB.toByteArray().toString(Charsets.UTF_8)

//        println("Сообщение [${command.name}] [$username] [$data] получено в ${getTimeStr()}")
        return Msg(command, username, usernameB, timeI.toLong(), timeB, data, dataB)
    }
}

/** Отправка сообщений.
 * Выбрасывает IllegalArgumentException, если отправляемые данные не соответствуют формату протокола.
 * Выбрасывает IOException, если не удалось отправить сообщение (закрыт сокет). **/
fun sendMsg(socket: SocketChannel, commandB: Byte, usernameB: ByteArray, timeB: ByteArray, dataB: ByteArray) {
    val msgB = mutableListOf<Byte>()
    val dataLen = dataB.size

    // Проверка на максимальный размер data
    if (dataLen > 16777215)
        throw IllegalArgumentException("The data field contains more than 16,777,215 bytes.")

    // Проверка корректной размерности полей
    val command = commandB.toUByte().toInt()
    if (command < 0 || command > 3 || usernameB.size != 10 || timeB.size != 4)
        throw IllegalArgumentException("The data sent was of the wrong size for sending.")

    // Формирование сообщения
    msgB.add(commandB)               // 1 байт: command (1 байт)
    val dataLenB = mutableListOf<Byte>()
    for (i in 2 downTo 0)
        msgB.add((dataLen ushr 8 * i).toByte())

    msgB.addAll(dataLenB)            // 2-4 байты: dataLen (3 байта)
    msgB.addAll(usernameB.toList())  // 5-14 байты: username (10 байт)
    msgB.addAll(timeB.toList())      // 15-18 байты: time (4 байта)
    msgB.addAll(dataB.toList())      // 19 и далее байты: data

    /** Отправка сформированного сообщения **/
    val buffer = ByteBuffer.wrap(msgB.toByteArray())
    socket.write(buffer)    // throws IOException
//    println("Сообщение [$command] [${usernameB.toString(Charsets.UTF_8)}] [${dataB.toString(Charsets.UTF_8)}] отправлено в ${getTimeStr()}")
}

fun sendMsg(socket: SocketChannel, command: Command, username: String, dataB: ByteArray, timeConst: Long = -1L) {
    val commandB = command.value.toByte()
    val unB = username.trim().toByteArray(Charsets.UTF_8)
    val usernameB = mutableListOf<Byte>()
    if (unB.size < 10)
        for (i in 1..(10 - unB.size))
            usernameB.add(0)
    usernameB.addAll(unB.toList())
    val time = if (timeConst == -1L) System.currentTimeMillis() / 1000L else timeConst
    val timeB = mutableListOf<Byte>()
    for (i in 3 downTo 0)
        timeB.add((time ushr 8 * i).toByte())

    sendMsg(socket, commandB, usernameB.toByteArray(), timeB.toByteArray(), dataB)
}

fun sendMsg(socket: SocketChannel, command: Command, username: String, data: String, timeConst: Long = -1L) =
    sendMsg(socket, command, username, data.toByteArray(Charsets.UTF_8), timeConst)

/** Метод проверки корректности сообщения на отправку файла. **/
fun checkSendFileMsg(msg: Msg): Boolean {
    if (msg.dataB.size > 16777215)
        return false
    val data = msg.dataB
    for (i in 0 until data.size - 1)
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && i != data.indices.first && i + 1 != data.indices.last)
            return true
    return false
}

fun getTimeStr(l: Long = System.currentTimeMillis() / 1000L): String? {
    val sdf = SimpleDateFormat("HH:mm")
    val date = Date(l * 1000)
    return sdf.format(date)
}