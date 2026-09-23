package com.example.GramStream

import android.content.Context
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object TdLibManager {

    private var client: Client? = null
    private var currentAuthState: TdApi.AuthorizationState? = null
    private var appContext: Context? = null

    private val activeStreamingFileIds = ConcurrentHashMap.newKeySet<Int>()
    private val messageCache = ConcurrentHashMap<Long, TdApi.Message>()
    private val fileCache = ConcurrentHashMap<Int, TdApi.File>()

    var onError: ((String) -> Unit)? = null

    var onAuthStateChanged: ((TdApi.AuthorizationState) -> Unit)? = null
        set(value) {
            field = value
            currentAuthState?.let { state -> value?.invoke(state) }
        }

    @Synchronized
    fun start(context: Context) {
        if (client != null) return
        appContext = context.applicationContext

        try {
            Client.execute(TdApi.SetLogVerbosityLevel(0))
        } catch (e: Throwable) {
            DebugLog.log("SetLogVerbosityLevel notice: ${e.message}")
        }

        client = Client.create({ update -> handleUpdate(context, update) }, null, null)

        startBackgroundVideoCleaner()
    }

    fun registerActiveFile(fileId: Int) {
        activeStreamingFileIds.add(fileId)
    }

    fun unregisterActiveFile(fileId: Int) {
        activeStreamingFileIds.remove(fileId)
        applyStrictStorageLimits()
    }

    fun clearActiveStream(fileId: Int = -1) {
        if (fileId != -1) {
            activeStreamingFileIds.remove(fileId)
        } else {
            activeStreamingFileIds.clear()
        }
        applyStrictStorageLimits()
    }

    private fun handleUpdate(context: Context, update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateFile -> {
                fileCache[update.file.id] = update.file
            }
            is TdApi.UpdateAuthorizationState -> {
                val state = update.authorizationState
                currentAuthState = state

                when (state) {
                    is TdApi.AuthorizationStateWaitTdlibParameters -> {
                        val params = TdApi.SetTdlibParameters().apply {
                            databaseDirectory = "${context.filesDir.absolutePath}/tdlib"
                            useMessageDatabase = true
                            useSecretChats = false
                            useFileDatabase = true
                            useChatInfoDatabase = true
                            apiId = Settings.apiId
                            apiHash = Settings.apiHash
                            systemLanguageCode = "en"
                            deviceModel = "Android"
                            applicationVersion = "0.2"
                        }

                        client?.send(params) { result ->
                            if (result is TdApi.Ok) {
                                applyStrictStorageLimits()
                            } else if (result is TdApi.Error) {
                                DebugLog.log("SetTdlibParameters error: ${result.code} - ${result.message}")
                                onError?.invoke(result.message)
                            }
                        }
                    }
                    is TdApi.AuthorizationStateWaitPhoneNumber,
                    is TdApi.AuthorizationStateWaitCode,
                    is TdApi.AuthorizationStateWaitPassword -> {
                        onAuthStateChanged?.invoke(state)
                    }
                    is TdApi.AuthorizationStateReady -> {
                        loadMainChats()
                        onAuthStateChanged?.invoke(state)
                    }
                    else -> {}
                }
            }
        }
    }

    fun applyStrictStorageLimits() {
        val maxBytes = Settings.maxCacheMb * 1024 * 1024

        client?.send(TdApi.SetOption("max_storage_size", TdApi.OptionValueInteger(maxBytes))) {}
        client?.send(TdApi.SetOption("max_time_ttl", TdApi.OptionValueInteger(1800L))) {}

        optimizeStorageToLimit(maxBytes)
        purgeTempDirectory(maxBytes)
    }

    fun optimizeStorageToLimit(maxBytes: Long) {
        client?.send(
            TdApi.OptimizeStorage(
                maxBytes,
                0,
                0,
                300,
                arrayOf(TdApi.FileTypeVideo(), TdApi.FileTypeDocument(), TdApi.FileTypeAnimation()),
                null,
                null,
                false,
                0
            )
        ) {}
    }

    private fun purgeTempDirectory(maxBytes: Long) {
        val ctx = appContext ?: return
        Thread {
            try {
                ctx.cacheDir?.deleteRecursively()

                val tempDir = File(ctx.filesDir, "tdlib_files/temp").takeIf { it.exists() }
                    ?: File(ctx.filesDir, "tdlib/files").takeIf { it.exists() }
                    ?: return@Thread

                val tempFiles = tempDir.walkTopDown().filter { it.isFile }.toList()
                var totalTempSize = tempFiles.sumOf { it.length() }

                if (totalTempSize > maxBytes) {
                    val activePaths = activeStreamingFileIds.mapNotNull { fileCache[it]?.local?.path }.toSet()
                    val now = System.currentTimeMillis()
                    val immunityMs = 10 * 60 * 1000L

                    val purgeCandidates = tempFiles.filter { file ->
                        val path = file.absolutePath
                        val isActive = activePaths.contains(path)
                        val isRecent = (now - file.lastModified()) < immunityMs

                        !isActive && !isRecent
                    }.sortedBy { it.lastModified() }

                    var freedBytes = 0L
                    for (file in purgeCandidates) {
                        if (totalTempSize <= maxBytes) break
                        val size = file.length()
                        if (file.delete()) {
                            totalTempSize -= size
                            freedBytes += size
                        }
                    }

                    if (freedBytes > 0) {
                        DebugLog.log("${freedBytes / (1024 * 1024)} MB freed — purging tdlib_files/temp")
                    }
                }
            } catch (e: Throwable) {
                DebugLog.log("Temp purge notice: ${e.message}")
            }
        }.start()
    }

    private fun startBackgroundVideoCleaner() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(3 * 60 * 1000L)
                    applyStrictStorageLimits()
                } catch (e: Throwable) {
                    break
                }
            }
        }.start()
    }

    fun optimizeStorage() {
        applyStrictStorageLimits()
    }

    fun ensureChatLoaded(chatId: Long) {
        val latch = CountDownLatch(1)
        client?.send(TdApi.GetChat(chatId)) { latch.countDown() }
        latch.await(5, TimeUnit.SECONDS)
    }

    fun getChatTitleBlocking(chatId: Long): String {
        ensureChatLoaded(chatId)
        val latch = CountDownLatch(1)
        var title = "Channel $chatId"
        client?.send(TdApi.GetChat(chatId)) { obj ->
            if (obj is TdApi.Chat) {
                title = obj.title
            }
            latch.countDown()
        } ?: return title
        latch.await(3, TimeUnit.SECONDS)
        return title
    }

    /**
     * Fetches all joined chat IDs from TDLib for Global Search.
     */
    fun getJoinedChatIdsBlocking(): Set<Long> {
        val latch = CountDownLatch(1)
        val result = mutableSetOf<Long>()
        client?.send(TdApi.GetChats(TdApi.ChatListMain(), 100)) { obj ->
            if (obj is TdApi.Chats) {
                result.addAll(obj.chatIds.toList())
            }
            latch.countDown()
        } ?: return emptySet()
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    fun getChatHistoryBlocking(chatId: Long, limit: Int = 100, fromMessageId: Long = 0L): List<TdApi.Message> {
        ensureChatLoaded(chatId)
        val latch = CountDownLatch(1)
        var result: List<TdApi.Message> = emptyList()
        val request = TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)
        client?.send(request) { obj ->
            when (obj) {
                is TdApi.Messages -> {
                    result = obj.messages.filterNotNull()
                    result.forEach { msg -> messageCache[msg.id] = msg }
                }
                is TdApi.Error -> DebugLog.log("GetChatHistory Error: ${obj.code} - ${obj.message}")
            }
            latch.countDown()
        } ?: return emptyList()
        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    fun searchVideoMessagesBlocking(chatId: Long, query: String): List<TdApi.Message> {
        ensureChatLoaded(chatId)
        val latch = CountDownLatch(1)
        var result: List<TdApi.Message> = emptyList()
        val request = TdApi.SearchChatMessages(
            chatId, null, query, null, 0, 0, 50, null
        )
        client?.send(request) { obj ->
            when (obj) {
                is TdApi.FoundChatMessages -> {
                    result = obj.messages.toList()
                    result.forEach { msg -> messageCache[msg.id] = msg }
                }
                is TdApi.Error -> DebugLog.log("SearchChatMessages Error: ${obj.code} - ${obj.message}")
            }
            latch.countDown()
        } ?: return emptyList()
        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    fun getMessageBlocking(chatId: Long, messageId: Long): TdApi.Message? {
        messageCache[messageId]?.let { return it }

        ensureChatLoaded(chatId)
        val latch = CountDownLatch(1)
        var result: TdApi.Message? = null
        client?.send(TdApi.GetMessage(chatId, messageId)) { obj ->
            when (obj) {
                is TdApi.Message -> {
                    result = obj
                    messageCache[messageId] = obj
                }
                is TdApi.Error -> DebugLog.log("GetMessage Error ($messageId): ${obj.code} - ${obj.message}")
            }
            latch.countDown()
        } ?: return null
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    fun getFileBlocking(fileId: Int): TdApi.File? {
        fileCache[fileId]?.let { return it }
        val latch = CountDownLatch(1)
        var result: TdApi.File? = null
        client?.send(TdApi.GetFile(fileId)) { obj ->
            when (obj) {
                is TdApi.File -> {
                    result = obj
                    fileCache[fileId] = obj
                }
                is TdApi.Error -> DebugLog.log("GetFile Error ($fileId): ${obj.code} - ${obj.message}")
            }
            latch.countDown()
        } ?: return null
        latch.await(10, TimeUnit.SECONDS)
        return result
    }

    fun submitPhoneNumber(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { obj ->
            if (obj is TdApi.Error) {
                DebugLog.log("SetAuthenticationPhoneNumber Error: ${obj.code} - ${obj.message}")
                onError?.invoke(obj.message)
            }
        }
    }

    fun submitCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { obj ->
            if (obj is TdApi.Error) {
                DebugLog.log("CheckAuthenticationCode Error: ${obj.code} - ${obj.message}")
                onError?.invoke(obj.message)
            }
        }
    }

    fun submitPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { obj ->
            if (obj is TdApi.Error) {
                DebugLog.log("CheckAuthenticationPassword Error: ${obj.code} - ${obj.message}")
                onError?.invoke(obj.message)
            }
        }
    }

    fun setPhoneNumber(phone: String) = submitPhoneNumber(phone)
    fun checkCode(code: String) = submitCode(code)
    fun checkPassword(password: String) = submitPassword(password)

    fun rawClient(): Client? = client
    fun getCachedFile(fileId: Int): TdApi.File? = fileCache[fileId]

    fun startDownload(fileId: Int, offset: Long, priority: Int = 32) {
        client?.send(TdApi.DownloadFile(fileId, priority, offset, 0, false)) { obj ->
            if (obj is TdApi.File) fileCache[fileId] = obj
        }
    }

    private fun loadMainChats() {
        client?.send(TdApi.LoadChats(null, 100)) {}
    }
}