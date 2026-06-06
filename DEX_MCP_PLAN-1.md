# DEX MCP Server — План реализации (внутри Android Service)

## Архитектура

Один Java HTTP-сервер, запущенный внутри фоновой службы Android (`android.app.Service`).

```
LLM агент (ПК)
    ↓ MCP (HTTP по Wi-Fi / ADB)
Android McpService (Внутри приложения)
    ├── McpServer (Socket HTTP)
    ├── dexlib2        (уже есть в проекте)
    └── smali/baksmali (уже есть в проекте)
```

**Преимущества:**
- Работает на самом устройстве в фоновом режиме (пользователь может переключаться между приложениями или свернуть редактор).
- Простая отладка по Wi-Fi или через `adb forward tcp:8788 tcp:8788`.
- Не раздувает APK лишними зависимостями (использует легкий `ServerSocket` и штатный UI диалог).

---

## Фоновая служба (McpService)

При старте службы запускается `ServerSocket` на выбранном порту, при остановке сокет закрывается и служба уничтожается. Для предотвращения закрытия системы при нехватке памяти служба работает как Foreground Service с типом `specialUse` (или стандартный фоновый поток с постоянным уведомлением).

---

## Инструменты MCP

### Чтение

**`dex_load`**
```json
{ "path": "/sdcard/app.apk" }
```
Загружает APK/DEX в память, возвращает количество классов и версию DEX.

---

**`dex_list_classes`**
```json
{ "filter": "com.example" }
```
Список классов с фильтром по пакету. Поддерживает пагинацию через курсор.

---

**`dex_get_class_outline`**
```json
{ "className": "Lcom/example/MainActivity;" }
```
Только сигнатуры методов и полей — без тела методов. Экономия контекста.

---

**`dex_get_method`**
```json
{
  "className": "Lcom/example/MainActivity;",
  "methodName": "isEncryptedNote"
}
```
Возвращает Smali только одного метода. Основной инструмент чтения.

---

**`dex_search`**
```json
{
  "query": "encrypt",
  "type": "code"
}
```
Типы: `class` | `method` | `field` | `string` | `code`. Поиск по Smali-коду.

---

### Модификация (str_replace подход)

**`dex_replace_in_method`**
```json
{
  "className": "Lcom/example/MainActivity;",
  "methodName": "isEncryptedNote",
  "old_str": "const/4 v0, 0x1\nreturn v0",
  "new_str": "const/4 v0, 0x0\nreturn v0"
}
```
Меняет конкретный фрагмент внутри метода. `old_str` должен быть уникален в теле метода.
Возвращает ошибку компилятора Smali если новый код невалиден — модель сразу исправляет.

---

**`dex_replace_method`**
```json
{
  "className": "Lcom/example/MainActivity;",
  "methodName": "isEncryptedNote",
  "smali": ".method public isEncryptedNote()Z\n    ...\n.end method"
}
```
Замена метода целиком. Используется когда изменений много.

---

### Сохранение

**`dex_save`**
```json
{
  "outputPath": "/sdcard/patched.apk",
  "stripDebug": false
}
```
Компилирует изменённые классы обратно в DEX и сохраняет APK.

---

## Workflow для LLM агента

```
1. dex_load("/sdcard/target.apk")
2. dex_search("license check", type="code")
3. dex_get_class_outline("Lcom/example/LicenseManager;")
4. dex_get_method("Lcom/example/LicenseManager;", "checkLicense")
5. dex_replace_in_method(className, method, old_str, new_str)
6. dex_save("/sdcard/patched.apk")
```

Контекст минимален: модель читает только нужный метод, меняет точный фрагмент через str_replace, не загружая весь класс.

---

## UI

В главное меню `MainActivity` добавлен пункт **"MCP Server"**, открывающий диалог:

- Кнопки **Старт / Стоп** (запуск и остановка службы `McpService`)
- Поле ввода порта (по умолчанию 8788)
- Отображение локального адреса и адресов LAN после запуска
- Лог-консоль с прокруткой — вывод входящих запросов и ошибок компиляции Smali в реальном времени
