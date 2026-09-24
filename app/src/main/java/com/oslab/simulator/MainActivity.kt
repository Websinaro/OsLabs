package com.oslab.simulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oslab.simulator.terminal.TerminalViewModel
import com.oslab.simulator.ui.theme.SimulatorTheme

/**
 * Entry point. Renders the simulator shell: a status bar with an Import
 * Update action, a virtual display panel (desktop icons), and a terminal
 * panel. Nothing on this screen touches real Android system state — the
 * Import button only ever hands a picked content Uri to
 * TerminalViewModel.importUpdate, which reads it through ZipImporter /
 * PackageValidator / UpdateEngine against the VirtualDevice.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimulatorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SimulatorShell()
                }
            }
        }
    }
}

@Composable
fun SimulatorShell(viewModel: TerminalViewModel = viewModel()) {
    val logLines = viewModel.logLines
    val status = viewModel.status
    val context = LocalContext.current

    val pickZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importUpdate(uri, context.contentResolver, context.cacheDir)
        }
    }

    // "*/*" is used (not "application/zip") because many file managers/zip
    // creators don't tag .zip files with a consistent MIME type; SAF still
    // scopes access to only the single file the user picks either way.

    Scaffold(
        topBar = {
            SimulatorTopBar(
                status = status,
                onImportClick = { pickZipLauncher.launch(arrayOf("*/*")) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            VirtualDisplayPanel(
                bootedOsName = viewModel.virtualOsLabel,
                apps = viewModel.desktopApps,
                onAppTap = { viewModel.launchApp(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            TerminalPanel(
                logLines = logLines,
                input = viewModel.inputText,
                onInputChange = { viewModel.inputText = it },
                onSubmit = { viewModel.runCommand(viewModel.inputText) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorTopBar(status: String, onImportClick: () -> Unit) {
    TopAppBar(
        title = { Text("OS Update Simulator") },
        actions = {
            TextButton(onClick = onImportClick) {
                Text("Import")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                StatusDot()
                Spacer(modifier = Modifier.width(6.dp))
                Text(status, fontSize = 13.sp)
            }
        }
    )
}

@Composable
fun StatusDot() {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(Color(0xFF4CAF50), shape = RoundedCornerShape(50))
    )
}

/**
 * The simulated device's screen: a desktop of app icons sourced from
 * VirtualDevice.desktopApps(). Tapping an icon calls back into the view
 * model, which runs it (if it has a main.vasm) through the bounded
 * interpreter — this composable never executes anything itself.
 */
@Composable
fun VirtualDisplayPanel(
    bootedOsName: String,
    apps: List<String>,
    onAppTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text("Virtual Display", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF101418), RoundedCornerShape(6.dp))
                .padding(12.dp)
        ) {
            Text(bootedOsName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("virtual desktop — no real system access", color = Color(0xFF8A9199), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Applications", color = Color(0xFF8A9199), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(apps) { app ->
                    AppIcon(name = app, onClick = { onAppTap(app) })
                }
            }
        }
    }
}

@Composable
fun AppIcon(name: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color(0xFF2A3138), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(name, color = Color.White, fontSize = 11.sp)
    }
}

/**
 * Debug console for the virtual machine. Commands typed here are only ever
 * interpreted by TerminalViewModel/VirtualDevice — never passed to
 * Runtime.exec, ProcessBuilder, or any real shell.
 */
@Composable
fun TerminalPanel(
    logLines: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }

    Column(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text("Terminal", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0B0F12), RoundedCornerShape(6.dp))
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        color = Color(0xFF7CFC9A),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("help") },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onSubmit) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Run command")
            }
        }
    }
}
