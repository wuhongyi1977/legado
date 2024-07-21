package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.ensureActive

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                play()
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }
    fun splitAndReform(lines: List<String>, maxLen:Int): Map<Int,String> {
        val c = lines.size
        AppLog.put("splitAndReform lines.size:$c")
        val result = mutableMapOf<Int,String>()
        var currentString = StringBuilder()
        var i = 0

        for (line in lines) {
            if (currentString.length + line.length + 1 > maxLen) {
                result.put(i, currentString.toString())
                AppLog.put("splitAndReform put:$i")
                currentString = StringBuilder()
            }
            if (currentString.isNotEmpty()) {
                currentString.append("\n")
            }
            currentString.append(line)
            i++
        }

        if (currentString.isNotEmpty()) {
            AppLog.put("splitAndReform put:$i-1")
            result.put(i-1, currentString.toString())
        }

        return result
    }
    fun getStringByIndex(map: Map<Int, String>, index: Int): Int? {
        // 获取所有键并排序
        val sortedKeys = map.keys.sorted()
        // 初始化要返回的键
        var resultKey: Int? = null

        // 迭代排序后的键，找到小于或等于给定索引的最大键
        for (key in sortedKeys) {
            if (key > index) {
                resultKey = key
                break
            }
        }

        // 返回找到的键对应的值
        return resultKey
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        val textLen = 1000
        val readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
        val bookname = ReadBook.book?.name?:""
        val bookauthor = ReadBook.book?.author?:""
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
            var result = tts.runCatching {
                speak("", TextToSpeech.QUEUE_FLUSH, null, null)
            }.getOrElse {
                AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                TextToSpeech.ERROR
            }
            if (result == TextToSpeech.ERROR) {
                AppLog.put("tts出错 尝试重新初始化")
                clearTTS()
                initTts()
                return@execute
            }
            val curtitle = ReadBook.curTextChapter?.title?:""
            val bookstr = "__jer&%*ry__" + bookname + "___"+ bookauthor + "___"+ curtitle
            val contentList = contentList
            val map = splitAndReform(contentList, textLen)
            for (i in nowSpeak until contentList.size) {
                ensureActive()
                var text = contentList[i]
                if (paragraphStartPos > 0 && i == nowSpeak) {
                    text = text.substring(paragraphStartPos)
                }
                if (text.matches(AppPattern.notReadAloudRegex)) {
                    continue
                }
                val mergeStrIndx = getStringByIndex(map, i)
                var mergeStr = ""
                if (mergeStrIndx != null){
                    mergeStr = "__jer&%*ry__" + map[mergeStrIndx]
                    if(mergeStrIndx == i + 5){
                        val mergeStrIndx2 = getStringByIndex(map, mergeStrIndx)
                        if (mergeStrIndx2 != null) {
                            val tempStr = map[mergeStrIndx2]
                            mergeStr = mergeStr + "__jer&%*ry__" + tempStr
                        }
                        else{
                            val nextText = ReadBook.nextTextChapter?.getNeedReadAloud(0, readAloudByPage, 0)
                            val nextTitle = ReadBook.nextTextChapter?.title
                            val nextList = (nextText?:"").split("\n")
                                .filter { it.isNotEmpty() }
                            val nextMap = splitAndReform(nextList, textLen)
                            val nextIndex = getStringByIndex(nextMap, 0)
                            val tempStr = nextMap[nextIndex]?:""
                            mergeStr = mergeStr + "__jer&%*ry__" + tempStr+ "___"+ nextTitle
                        }
                    }
                }

                result = tts.runCatching {
                    speak(text + bookstr + mergeStr, TextToSpeech.QUEUE_ADD, null, AppConst.APP_TAG + i)
                }.getOrElse {
                    AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                    TextToSpeech.ERROR
                }
                if (result == TextToSpeech.ERROR) {
                    AppLog.put("tts朗读出错:$text")
                }
            }
            LogUtils.d(TAG, "朗读内容添加完成")
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    override fun playStop() {
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            textChapter?.let {
                if (readAloudNumber + 1 > it.getReadLength(pageIndex + 1)) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
                upTtsProgress(readAloudNumber + 1)
            }
        }

        override fun onDone(s: String) {
            LogUtils.d(TAG, "onDone utteranceId:$s")
            //跳过全标点段落
            do {
                readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    nextChapter()
                    return
                }
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            val msg =
                "$TAG onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            textChapter?.let {
                if (readAloudNumber + start > it.getReadLength(pageIndex + 1)) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + start)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            //nothing
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}