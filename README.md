# Формат протокола:

| command | dataLength | username |  time   |         data         |
|:-------:|:----------:|:--------:|:-------:|:--------------------:|
| 1 byte  |  3 bytes   | 10 bytes | 4 bytes | 0 - 16 777 215 bytes |

## command:
- `00` - отправка сообщения с содержимым `data`,
- `01` - отправка файла, байты которого находятся в `data`,
- `10` - запрос/ответ на аутентификацию,
- `11` - отключение.

## dataLength:
- длина поля `data`,
- max значение = 1111 1111 1111 1111 1111 1111 = 16 777 215.

## username:
- имя пользователя, которое будет отображаться в чате;
- имя пользователя не менее 1 символа, но не более 10; 
- имя пользователя `__Server__` зарезервировано под сервер.

## time:
- поле времени отправки сообщения.

## data:
- поле данных,
- max размер = 16 777 215 байт ~ 16 Мбайт,
- при отправке файла следующий формат: [байты названия файла\] [0\] [0\] [байты файла\].