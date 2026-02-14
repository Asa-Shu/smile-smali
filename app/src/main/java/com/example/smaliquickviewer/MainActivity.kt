package com.example.smaliquickviewer

import android.net.Uri
import android.os.Bundle
import java.io.FileInputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    private val vm by lazy {
        val appContext = applicationContext
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ViewerViewModel(appContext) as T
            }
        })[ViewerViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ViewerScreen(vm) } }
    }
}

data class SmaliClass(
    val className: String,
    val methods: List<String>,
    val body: String,
    val invokeTargets: Set<String>
)

data class UiState(
    val loading: Boolean = false,
    val query: String = "",
    val classes: List<SmaliClass> = emptyList(),
    val selectedClass: SmaliClass? = null,
    val error: String? = null,
    val sourceSummary: String = ""
)

class ViewerViewModel(
    private val context: android.content.Context
) : ViewModel() {
    private val contentResolver = context.contentResolver
    private val appSmaliPrefix = "L${context.packageName.replace('.', '/')}"
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadBundledSampleApk()
    }

    fun onQueryChange(q: String) {
        _uiState.value = _uiState.value.copy(query = q)
    }

    fun openApk(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                contentResolver.openInputStream(uri)?.use { apkStream ->
                    readSmaliLikeClasses(apkStream.readBytes())
                } ?: error("APK を開けませんでした")
            }.onSuccess { classes ->
                _uiState.value = UiState(
                    classes = classes,
                    selectedClass = classes.firstOrNull(),
                    loading = false,
                    sourceSummary = "選択した APK: 全クラス ${classes.size} 件"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(loading = false, error = err.message)
            }
        }
    }

    private fun loadBundledSampleApk() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                FileInputStream(context.applicationInfo.sourceDir).use { apkStream ->
                    readSmaliLikeClasses(apkStream.readBytes())
                }
            }.onSuccess { allClasses ->
                val appOnlyClasses = allClasses.filter { it.className.startsWith(appSmaliPrefix) }
                _uiState.value = UiState(
                    classes = appOnlyClasses,
                    selectedClass = appOnlyClasses.firstOrNull(),
                    loading = false,
                    sourceSummary = "起動時サンプル: アプリ本体 ${appOnlyClasses.size} 件 / APK 全体 ${allClasses.size} 件"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(loading = false, error = err.message)
            }
        }
    }

    fun selectClass(name: String) {
        val target = _uiState.value.classes.firstOrNull { it.className == name } ?: return
        _uiState.value = _uiState.value.copy(selectedClass = target)
    }

    private fun readSmaliLikeClasses(apkBytes: ByteArray): List<SmaliClass> {
        val result = mutableListOf<SmaliClass>()
        ZipInputStream(apkBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.matches(Regex("classes\\d*\\.dex"))) continue
                val dexBytes = zip.readBytes()
                val dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), dexBytes.inputStream())
                dex.classes.forEach { classDef ->
                    val methods = classDef.methods.map { it.name }
                    val invokes = mutableSetOf<String>()
                    val body = buildString {
                        appendLine(".class ${classDef.type}")
                        appendLine(".super ${classDef.superclass ?: "Ljava/lang/Object;"}")
                        classDef.methods.forEach { method ->
                            appendLine("")
                            appendLine(".method ${method.name}${method.parameterTypes.joinToString(prefix = "(", postfix = ")")}${method.returnType}")
                            method.implementation?.instructions?.forEach { inst ->
                                append("    ")
                                append(inst.opcode.name.lowercase())
                                if (inst is ReferenceInstruction && inst.reference is MethodReference) {
                                    val ref = inst.reference as MethodReference
                                    val target = "${ref.definingClass}->${ref.name}"
                                    invokes += ref.definingClass
                                    append("  # $target")
                                }
                                appendLine()
                            }
                            if (method.returnType == "V" && method.implementation == null) {
                                appendLine("    return-void")
                            }
                            appendLine(".end method")
                        }
                    }
                    result += SmaliClass(classDef.type, methods, body, invokes)
                }
            }
        }
        return result.sortedBy { it.className }
    }
}

@Composable
fun ViewerScreen(vm: ViewerViewModel) {
    val state by vm.uiState.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> if (uri != null) vm.openApk(uri) }
    )

    val query = state.query.trim()
    val filtered = if (query.isBlank()) {
        state.classes
    } else {
        state.classes.filter {
            it.className.contains(query, true) || it.methods.any { m -> m.contains(query, true) }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { launcher.launch("application/vnd.android.package-archive") }) {
                Text("APKを選択")
            }
            Text("起動時にサンプルとしてこのアプリ自身のAPKを読み込みます")
            if (state.sourceSummary.isNotBlank()) {
                Text(state.sourceSummary, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("クラス/メソッド検索") }
            )
            if (!state.loading && state.error == null && state.classes.isNotEmpty() && query.isBlank()) {
                Text("無検索: ${state.classes.size}件を表示中", color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let { Text("Error: $it", color = Color.Red) }
            if (state.loading) {
                Text("解析中...")
            }
            if (!state.loading && state.error == null && state.classes.isEmpty()) {
                Text("クラスが見つかりませんでした（DEXが含まれていない可能性があります）", color = Color.Gray)
            }
            if (!state.loading && state.classes.isNotEmpty() && filtered.isEmpty()) {
                Text("検索条件に一致するクラス/メソッドがありません", color = Color.Gray)
            }
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(0.38f)) {
                    if (filtered.isEmpty()) {
                        item {
                            val message = when {
                                state.loading -> "解析中..."
                                state.error != null -> "読み込みに失敗しました"
                                state.classes.isEmpty() -> "クラスが見つかりませんでした（DEXが含まれていない可能性があります）"
                                else -> "検索条件に一致するクラス/メソッドがありません（検索語: $query）"
                            }
                            Text(
                                text = message,
                                color = Color.Gray,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    } else {
                        items(filtered) { clazz ->
                            Text(
                                text = clazz.className,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.selectClass(clazz.className) }
                                    .padding(8.dp),
                                fontWeight = if (state.selectedClass?.className == clazz.className) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                VerticalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp)
                )
                LazyColumn(modifier = Modifier.weight(0.62f)) {
                    val selected = state.selectedClass
                    if (selected != null && selected.body.isNotBlank()) {
                        val smaliLines = selected.body.lines()
                        itemsIndexed(smaliLines) { index, line ->
                            val trimmed = line.trimStart()
                            val isMethodStart = trimmed.startsWith(".method ")
                            val isMethodEnd = trimmed == ".end method"

                            if (isMethodStart && index > 0) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            val invoke = line.substringAfter("# ", "")
                            Text(
                                text = highlightLine(line),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = invoke.contains("->")) {
                                        val targetClass = invoke.substringBefore("->")
                                        vm.selectClass(targetClass)
                                    }
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                    .background(
                                        color = if (isMethodStart || isMethodEnd) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                        } else {
                                            Color.Transparent
                                        },
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        item {
                            Text(
                                text = "クラスを選択するとここに内容を表示します",
                                color = Color.Gray,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun highlightLine(line: String): AnnotatedString = buildAnnotatedString {
    when {
        line.trimStart().startsWith(".") -> withStyle(SpanStyle(color = Color(0xFF1565C0))) { append(line) }
        line.contains("#") -> {
            append(line.substringBefore("#"))
            withStyle(SpanStyle(color = Color(0xFF2E7D32))) { append("#" + line.substringAfter("#")) }
        }
        line.contains("invoke") -> withStyle(SpanStyle(color = Color(0xFFD84315))) { append(line) }
        else -> append(line)
    }
}
