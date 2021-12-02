# Формат команды запуска приложения
`java -jar TCPClient.jar [-a inetAddress] [-p port] [-r readBufferSize]`

- `inetAddress` - адрес сервера (по умолчанию localhost),
- `port` - порт сервера (по умолчанию 8888),
- `readBufferSize` - размер буфера для чтения data (по умолчанию 128 Кбайт)

# Отправка команд пользователем
- `--file filePath` - отправить файл по абсолютному пути `filePath`,
- `--exit` - выйти из чата,
- сочетание клавиш `Ctrl+D` - выйти из чата.