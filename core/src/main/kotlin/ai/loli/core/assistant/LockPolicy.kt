package ai.loli.core.assistant

/**
 * Что ассистенту можно делать, пока телефон заблокирован. Настраивается пользователем;
 * по умолчанию — записывать новое, таймеры/будильники и звонки, но не показывать и не менять личные данные
 * и не открывать приложения.
 */
data class LockPolicy(
    /** Новые заметки, расходы, задачи, напоминания, «запомни», дописать в список. */
    val create: Boolean = true,
    /** Таймер, будильник, фонарик, музыка, громкость, заряд, «не беспокоить». */
    val basicDevice: Boolean = true,
    /** Звонки и сообщения. */
    val calls: Boolean = true,
    /** Показывать записи: расходы, задачи, память, поиск, план на день. */
    val view: Boolean = false,
    /** Изменять и удалять записи. */
    val edit: Boolean = false,
    /** Открывать приложения, сайты, камеру, настройки. */
    val apps: Boolean = false,
) {
    /** Уровень для простых настроек: «ничего», «только безопасное», «всё» или своя настройка. */
    enum class Level { NONE, SAFE, ALL, CUSTOM }

    val level: Level get() = when (this) {
        NONE -> Level.NONE
        SAFE -> Level.SAFE
        ALL -> Level.ALL
        else -> Level.CUSTOM
    }

    companion object {
        val NONE = LockPolicy(create = false, basicDevice = false, calls = false, view = false, edit = false, apps = false)
        /** По умолчанию: записать, таймер, погода, музыка, звонки — но не показывать личное. */
        val SAFE = LockPolicy()
        val ALL = LockPolicy(create = true, basicDevice = true, calls = true, view = true, edit = true, apps = true)

        fun of(level: Level): LockPolicy? = when (level) {
            Level.NONE -> NONE
            Level.SAFE -> SAFE
            Level.ALL -> ALL
            Level.CUSTOM -> null
        }
    }

    /** Хоть что-то разрешено: иначе на блокировке Лоли не отвечает вообще. */
    val any: Boolean get() = create || basicDevice || calls || view || edit || apps

    /** Навыки: погода и справка — если Лоли вообще разрешена на блокировке; имя, место — как запись; радио и таймеры — как телефон. */
    fun allowsSkill(access: SkillAccess): Boolean = when (access) {
        SkillAccess.PUBLIC -> any
        SkillAccess.CREATE -> create
        SkillAccess.DEVICE -> basicDevice
        SkillAccess.VIEW -> view
        SkillAccess.PRIVATE -> false
    }

    fun allows(action: AssistantAction): Boolean = when (action) {
        is AssistantAction.CreateNote, is AssistantAction.CreateExpense, is AssistantAction.CreateTask,
        is AssistantAction.CreateReminder, is AssistantAction.Remember, is AssistantAction.AppendNote,
        is AssistantAction.AddToList, is AssistantAction.AddBirthday, is AssistantAction.CreateSecretNote, is AssistantAction.AddSubscription,
        is AssistantAction.PutThing, is AssistantAction.AddDebt, is AssistantAction.CreateList, is AssistantAction.AddDeadline -> create
        is AssistantAction.Clarify -> true
        is AssistantAction.QueryList, is AssistantAction.QueryBirthdays, AssistantAction.QueryRoutines, is AssistantAction.QuerySubscriptions,
        is AssistantAction.QueryDebts, AssistantAction.QueryLists, is AssistantAction.QueryDeadlines -> view
        // Список уходит другому человеку: нужно и смотреть записи, и писать сообщения.
        is AssistantAction.SendList -> view && calls
        is AssistantAction.QueryExpenses, is AssistantAction.QueryTasks, AssistantAction.QueryReminders,
        is AssistantAction.QueryMemories, is AssistantAction.Search, is AssistantAction.Agenda -> view
        is AssistantAction.Device -> when (val c = action.command) {
            is DeviceCommand.Timer, is DeviceCommand.Alarm, is DeviceCommand.Flashlight, DeviceCommand.Battery,
            is DeviceCommand.Media, is DeviceCommand.Volume, is DeviceCommand.DoNotDisturb, is DeviceCommand.Brightness, is DeviceCommand.Relax, is DeviceCommand.Focus, is DeviceCommand.SleepMode -> basicDevice
            // Заблокировать экран можно всегда; остальные системные кнопки — как открытие приложений.
            is DeviceCommand.Global -> c.action == GlobalAction.LOCK || apps
            is DeviceCommand.Call, is DeviceCommand.Message, is DeviceCommand.Driving -> calls
            // Где машина — личное место: запомнить можно как запись, посмотреть — как просмотр записей.
            is DeviceCommand.Parking -> if (c.save) create else view
            // Экран копии показывает все данные: на экране блокировки закрыт.
            is DeviceCommand.OpenBackup -> view && edit
            else -> apps
        }
        else -> edit
    }
}

/** Насколько личное действие навыка — для правил экрана блокировки. */
enum class SkillAccess { PUBLIC, CREATE, DEVICE, VIEW, PRIVATE }
