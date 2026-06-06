# DEX MCP Server — План реализации

## Архитектура

Один Java HTTP-сервер поверх уже готового движка проекта. Никакого Node.js.

```
LLM агент
    ↓ MCP (Streamable HTTP)
Java HTTP MCP Server  ←─── Javalin
    ├── dexlib2        (уже есть в проекте)
    └── smali/baksmali (уже есть в проекте)
```

**Почему не Node.js прослойка:**
- Лишний процесс и зависимость
- Двойная сериализация (MCP → Node → stdin/stdout → Java)
- Javalin — легковесный HTTP сервер, одна зависимость
- Весь движок уже на Java, HTTP сервер пишется прямо внутри него

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

## Реализация

### HTTP MCP сервер (Javalin)

```java
// McpServer.java
Javalin app = Javalin.create().start(8788);

app.post("/mcp", ctx -> {
    McpRequest req = ctx.bodyAsClass(McpRequest.class);
    McpResponse resp = dispatcher.handle(req);
    ctx.json(resp);
});
```

### UI

В главное меню приложения добавить пункт **"MCP Server"**, открывающий диалог:

- Кнопки **Старт / Стоп**
- Поле ввода порта (по умолчанию 8788)
- Отображение локального адреса и адресов LAN после запуска
- Лог-консоль с прокруткой — вывод входящих запросов и ошибок компиляции Smali в реальном времени

---

### Gradle — сборка Fat JAR (без Android UI)

```gradle
task buildMcpJar(type: Jar) {
    archiveClassifier = 'mcp'
    from sourceSets.main.output
    dependsOn configurations.runtimeClasspath
    from {
        configurations.runtimeClasspath.collect {
            it.isDirectory() ? it : zipTree(it)
        }
    }
    manifest {
        attributes 'Main-Class': 'modder.hub.dexeditor.mcp.McpServer'
    }
    exclude 'android/**', 'androidx/**'
}
```
