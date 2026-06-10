# Dex-Editor-Android + MCP Server (Fork)

> 🇬🇧 **[Read in English](README.md)**

Это форк оригинального проекта advanced Android DEX file editor, в который добавлена поддержка **MCP (Model Context Protocol)**. 

Этот форк позволяет подключить вашего локального ИИ-агента (например, Claude Desktop, Pi или любой другой MCP-клиент) напрямую к вашему Android-устройству для чтения, поиска, модификации и сборки DEX-файлов.

---

## 🚀 Новые возможности в этом форке

- **Встроенный MCP HTTP Сервер**: Работает внутри фоновой службы Android (`McpService`) с Foreground-уведомлением. Сервер не отключается при сворачивании приложения.
- **Инструменты для LLM агента (Tools)**:
  - `dex_load` — Загрузка DEX-файлов (включая мульти-декс через перечисление путей).
  - `dex_list_classes` — Получение списка классов с фильтрацией и постраничной навигацией.
  - `dex_get_class_outline` — Получение сигнатур полей и методов (без тел методов) для экономии контекста модели.
  - `dex_get_method` — Чтение Smali-кода одного конкретного метода (поддерживает полный `methodSignature` для перегрузок).
  - `dex_get_java` — Декомпиляция Smali класса в полноценный Java-код через встроенный JADX.
  - `dex_search` — Быстрый поиск в пуле по классам, методам, полям, строкам или коду.
  - `dex_find_usages` — Глубокий поиск Xref (кто вызывает метод, обращается к полю, наследует класс).
  - `dex_replace_in_method` — Умная точечная замена подстроки в методе (`str_replace` подход) с моментальной проверкой компилятора и проверкой уникальности.
  - `dex_replace_method` — Полная замена тела метода (поддерживает полный `methodSignature` для перегрузок).
  - `dex_create_class` — Создание и компиляция абсолютно нового класса с нуля из Smali кода.
  - `dex_remove_class` — Удаление (вырезание) класса из DEX.
  - `dex_list_methods` — Список методов класса с полными сигнатурами, безопасными для перегрузок.
  - `dex_list_fields` — Список полей класса с полными сигнатурами полей.
  - `dex_get_strings` — Список строковых констант с фильтрацией и пагинацией.
  - `dex_save` — Сборка и сохранение измененных файлов на диск, с точным `outputPath` для single-dex.
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
