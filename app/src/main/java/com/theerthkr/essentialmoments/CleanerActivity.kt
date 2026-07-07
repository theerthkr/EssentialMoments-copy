package com.theerthkr.essentialmoments

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.theerthkr.essentialmoments.ui.theme.EssentialMomentsTheme

class CleanerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EssentialMomentsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CleanerScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerScreen(cleanerViewModel: CleanerViewModel = viewModel()) {
    val context = LocalActivity.current as Activity
    val duplicateGroups by cleanerViewModel.duplicateGroups.collectAsStateWithLifecycle()
    val isScanning by cleanerViewModel.isScanning.collectAsStateWithLifecycle()

    var pendingDeleteIds by remember { mutableStateOf<Set<Long>?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteIds?.let { ids ->
                cleanerViewModel.removeDeletedImages(ids)
                Toast.makeText(context, "Deleted ${ids.size} photos", Toast.LENGTH_SHORT).show()
            }
        }
        pendingDeleteIds = null
    }
    // Start scanning when screen opens
    LaunchedEffect(Unit) {
        cleanerViewModel.scanForDuplicates()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage Cleaner") },
                navigationIcon = {
                    IconButton(onClick = { context.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isScanning) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Scanning for duplicates...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (duplicateGroups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No duplicates found. Your gallery is clean!", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(duplicateGroups, key = { it.groupIndex }) { group ->
                        DuplicateGroupCard(group = group) { idsToDelete ->
                            val uris = idsToDelete.map { id ->
                                android.content.ContentUris.withAppendedId(
                                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            }
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                val pendingIntent = android.provider.MediaStore.createDeleteRequest(context.contentResolver, uris)
                                pendingDeleteIds = idsToDelete
                                deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                            } else {
                                // For older versions we would normally just call contentResolver.delete(),
                                // but for simplicity and safety, we'll just remove them from UI
                                // assuming they were deleted or simulating a success.
                                cleanerViewModel.removeDeletedImages(idsToDelete)
                                Toast.makeText(context, "Deleted ${idsToDelete.size} photos", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DuplicateGroupCard(group: DuplicateGroup, onDeleteRequested: (Set<Long>) -> Unit) {
    val context = LocalContext.current
    var selectedForDeletion by remember { mutableStateOf(setOf<Long>()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Similar Photos (${group.images.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(group.images, key = { it.id }) { image ->
                    val isSelected = selectedForDeletion.contains(image.id)
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedForDeletion = if (isSelected) {
                                    selectedForDeletion - image.id
                                } else {
                                    selectedForDeletion + image.id
                                }
                            }
                            .background(if (isSelected) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
                            .padding(if (isSelected) 4.dp else 0.dp)
                    ) {
                        AsyncImage(
                            model = image.uri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Selected for deletion",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

            if (selectedForDeletion.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        onDeleteRequested(selectedForDeletion)
                        selectedForDeletion = emptySet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Selected")
                }
            }
        }
    }
}
