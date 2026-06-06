# Dex-Editor-Android + MCP Server (Fork)

Это форк оригинального проекта advanced Android DEX file editor, в который добавлена поддержка **MCP (Model Context Protocol)**. 

Этот форк позволяет подключить вашего локального ИИ-агента (например, Claude Desktop, Pi или любой другой MCP-клиент) напрямую к вашему Android-устройству для чтения, поиска, модификации и сборки DEX/APK файлов.

---

## 🚀 Новые возможности в этом форке

- **Встроенный MCP HTTP Сервер**: Работает внутри фоновой службы Android (`McpService`) с Foreground-уведомлением. Сервер не отключается при сворачивании приложения.
- **Инструменты для LLM агента (Tools)**:
  - `dex_load` — Загрузка DEX/APK файлов в память.
  - `dex_list_classes` — Получение списка классов с фильтрацией и постраничной навигацией.
  - `dex_get_class_outline` — Получение сигнатур полей и методов (без тяжелых тел методов) для экономии контекстного окна модели.
  - `dex_get_method` — Декомпиляция и чтение Smali-кода одного конкретного метода.
  - `dex_search` — Быстрый поиск в DEX по классам, методам, полям, строкам или коду.
  - `dex_replace_in_method` — Умная замена подстроки в методе (`str_replace` подход). Модель правит конкретный участок, а компилятор Smali сразу проверяет корректность сборки.
  - `dex_replace_method` — Полная замена тела метода.
  - `dex_save` — Компиляция измененных классов обратно в DEX и сохранение файла.
- **Двусторонняя синхронизация**:
  - Если LLM-агент загружает файл через `dex_load`, путь к нему тут же отображается на главном экране приложения.
  - Если пользователь сам выбирает файл на главном экране приложения (через встроенный проводник или вставкой пути), и MCP сервер при этом запущен, файл автоматически загружается в память сервера.
- **Контроль и логирование в UI**:
  - Кнопка **MCP Server** добавлена в меню главного экрана.
  - Панель управления позволяет задать порт, запустить/остановить службу, скопировать логи в буфер обмена одной кнопкой и просматривать входящие запросы от модели и сообщения компилятора в реальном времени.


---

<details>
<summary><b>Оригинальное описание проекта (Original README)</b></summary>

## My first open source Project 😀🇮🇳
# Dex-Editor-Android
[![Android CI](https://github.com/developer-krushna/Dex-Editor-Android/actions/workflows/android.yml/badge.svg)](https://github.com/developer-krushna/Dex-Editor-Android/actions/workflows/android.yml)
A work-in-progress multifunctional advanced *Android **DEX** file editor* for Android, using mainly [smali](https://github.com/google/smali) & [dexlib2](https://github.com/google/smali/tree/main/dexlib2).

### Available decompilers
- [JADX](https://github.com/skylot/jadx)

### Available features
- Dex Smali classes TreeView
- Smali navigation (methods, fields and strings list)
- Decompile single smali classes
- Decompiling single smali method bodies to java
- Batch class deletion
- Smali method flow diagram
- Editing Smali with best code editor
- Batch class editor and navigator
- Multi dex loader and compiler
- Smali full featured search and replacement
- Custom editor selection menu
- Faster Dex compilation with real time progress update
- Supported DEX version 40 and 41

### Environment
- **Gradle Version**: 9.5.0
- **Android Gradle Plugin (AGP)**: 9.2.1
- **JDK**: 17 or 21 (Required for Gradle 9+)
- **Min SDK**: API 24
- **Compile SDK**: API 37

</details>
