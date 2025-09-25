package io.heckel.ntfy.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Editable
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.activity.viewModels
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.random.Random


class DetailActivity : AppCompatActivity(), ActionMode.Callback, NotificationFragment.NotificationSettingsListener {
    private val viewModel by viewModels<DetailViewModel> {
        DetailViewModelFactory((application as Application).repository)
    }
    private val repository by lazy { (application as Application).repository }
    private val api = ApiService()
    private val messenger = FirebaseMessenger()
    private var notifier: NotificationService? = null // Context-dependent
    private var appBaseUrl: String? = null // Context-dependent

    // Which subscription are we looking at
    private var subscriptionId: Long = 0L // Set in onCreate()
    private var subscriptionBaseUrl: String = "" // Set in onCreate()
    private var subscriptionTopic: String = "" // Set in onCreate()
    private var subscriptionDisplayName: String = "" // Set in onCreate() & updated by options menu!
    private var subscriptionInstant: Boolean = false // Set in onCreate() & updated by options menu!
    private var subscriptionMutedUntil: Long = 0L // Set in onCreate() & updated by options menu!

    // UI elements
    private lateinit var adapter: DetailAdapter
    private lateinit var mainList: RecyclerView
    private lateinit var mainListContainer: SwipeRefreshLayout
    private lateinit var menu: Menu
    
    // Message input UI elements
    private lateinit var messageInputContainer: View
    private lateinit var priorityLayout: TextInputLayout
    private lateinit var priorityText: AutoCompleteTextView
    private lateinit var titleInputLayout: TextInputLayout
    private lateinit var titleInput: TextInputEditText
    private lateinit var tagsInputLayout: TextInputLayout
    private lateinit var tagsInput: TextInputEditText
    private lateinit var attachFileButton: MaterialButton
    private lateinit var markdownToggleButton: MaterialButton
    private lateinit var messageInputLayout: TextInputLayout
    private lateinit var messageInput: TextInputEditText
    private lateinit var sendButton: FloatingActionButton
    private lateinit var attachmentInfo: View
    private lateinit var attachmentInfoText: TextView
    private lateinit var attachmentRemoveButton: ImageButton
    private lateinit var markdownPreview: View
    private lateinit var markdownPreviewText: TextView
    
    // Message input state
    private var selectedPriority: Int = PRIORITY_DEFAULT
    private var selectedAttachmentUri: Uri? = null
    private var isMarkdownMode: Boolean = false
    private var selectedTags: MutableList<String> = mutableListOf()

    // Action mode stuff
    private var actionMode: ActionMode? = null
    
    // File picker
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onAttachmentSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        Log.d(TAG, "Create $this")

        // Dependencies that depend on Context
        notifier = NotificationService(this)
        appBaseUrl = getString(R.string.app_base_url)

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Handle direct deep links to topic "ntfy://..."
        val url = intent?.data
        if (intent?.action == ACTION_VIEW && url != null) {
            maybeSubscribeAndLoadView(url)
        } else {
            loadView()
        }
    }

    private fun maybeSubscribeAndLoadView(url: Uri) {
        if (url.pathSegments.size != 1) {
            Log.w(TAG, "Invalid link $url. Aborting.")
            finish()
            return
        }
        val secure = url.getBooleanQueryParameter("secure", true)
        val baseUrl = if (secure) "https://${url.host}" else "http://${url.host}"
        val topic = url.pathSegments.first()
        title = topicShortUrl(baseUrl, topic)

        // Subscribe to topic if it doesn't already exist
        lifecycleScope.launch(Dispatchers.IO) {
            var subscription = repository.getSubscription(baseUrl, topic)
            if (subscription == null) {
                val instant = baseUrl != appBaseUrl
                subscription = Subscription(
                    id = randomSubscriptionId(),
                    baseUrl = baseUrl,
                    topic = topic,
                    instant = instant,
                    dedicatedChannels = false,
                    mutedUntil = 0,
                    minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
                    autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
                    insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
                    lastNotificationId = null,
                    icon = null,
                    upAppId = null,
                    upConnectorToken = null,
                    displayName = null,
                    totalCount = 0,
                    newCount = 0,
                    lastActive = Date().time/1000
                )
                repository.addSubscription(subscription)

                // Subscribe to Firebase topic if ntfy.sh (even if instant, just to be sure!)
                if (baseUrl == appBaseUrl) {
                    Log.d(TAG, "Subscribing to Firebase topic $topic")
                    messenger.subscribe(topic)
                }

                // Fetch cached messages
                try {
                    val user = repository.getUser(subscription.baseUrl) // May be null
                    val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic, user)
                    notifications.forEach { notification -> repository.addNotification(notification) }
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to fetch notifications: ${e.message}", e)
                }

                runOnUiThread {
                    val message = getString(R.string.detail_deep_link_subscribed_toast_message, topicShortUrl(baseUrl, topic))
                    Toast.makeText(this@DetailActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            // Add extras needed in loadView(); normally these are added in MainActivity
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(subscription))
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
            intent.putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)

            runOnUiThread {
                loadView()
            }
        }
    }

    private fun loadView() {
        // Get extras required for the return to the main activity
        subscriptionId = intent.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, 0)
        subscriptionBaseUrl = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL) ?: return
        subscriptionTopic = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC) ?: return
        subscriptionDisplayName = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME) ?: return
        subscriptionInstant = intent.getBooleanExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, false)
        subscriptionMutedUntil = intent.getLongExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, 0L)

        // Set title
        val subscriptionBaseUrl = intent.getStringExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL) ?: return
        val topicUrl = topicShortUrl(subscriptionBaseUrl, subscriptionTopic)
        title = subscriptionDisplayName

        // Set "how to instructions"
        val howToExample: TextView = findViewById(R.id.detail_how_to_example)
        howToExample.linksClickable = true

        val howToText = getString(R.string.detail_how_to_example, topicUrl)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            howToExample.text = Html.fromHtml(howToText, Html.FROM_HTML_MODE_LEGACY)
        } else {
            howToExample.text = Html.fromHtml(howToText)
        }

        // Swipe to refresh
        mainListContainer = findViewById(R.id.detail_notification_list_container)
        mainListContainer.setOnRefreshListener { refresh() }
        mainListContainer.setColorSchemeResources(Colors.refreshProgressIndicator)

        // Update main list based on viewModel (& its datasource/livedata)
        val noEntriesText: View = findViewById(R.id.detail_no_notifications)
        val onNotificationClick = { n: Notification -> onNotificationClick(n) }
        val onNotificationLongClick = { n: Notification -> onNotificationLongClick(n) }

        adapter = DetailAdapter(this, lifecycleScope, repository, onNotificationClick, onNotificationLongClick)
        mainList = findViewById(R.id.detail_notification_list)
        mainList.adapter = adapter

        viewModel.list(subscriptionId).observe(this) {
            it?.let {
                // Show list view
                adapter.submitList(it as MutableList<Notification>)
                if (it.isEmpty()) {
                    mainListContainer.visibility = View.GONE
                    noEntriesText.visibility = View.VISIBLE
                } else {
                    mainListContainer.visibility = View.VISIBLE
                    noEntriesText.visibility = View.GONE
                }

                // Cancel notifications that still have popups
                maybeCancelNotificationPopups(it)
            }
        }

        // Swipe to remove
        val itemTouchCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {
                val notification = adapter.get(viewHolder.absoluteAdapterPosition)
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.markAsDeleted(notification.id)
                }
                val snackbar = Snackbar.make(mainList, R.string.detail_item_snack_deleted, Snackbar.LENGTH_SHORT)
                snackbar.setAction(R.string.detail_item_snack_undo) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        repository.undeleteNotification(notification.id)
                    }
                }
                snackbar.show()
            }
        }
        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(mainList)

        // Scroll up when new notification is added
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0) {
                    Log.d(TAG, "$itemCount item(s) inserted at 0, scrolling to the top")
                    mainList.scrollToPosition(positionStart)
                }
            }
        })

        // React to changes in fast delivery setting
        repository.getSubscriptionIdsWithInstantStatusLiveData().observe(this) {
            SubscriberServiceManager.refresh(this)
        }

        // Mark this subscription as "open" so we don't receive notifications for it
        repository.detailViewSubscriptionId.set(subscriptionId)

        // Stop insistent playback (if running, otherwise it'll throw)
        try {
            repository.mediaPlayer.stop()
        } catch (_: Exception) {
            // Ignore errors
        }

        // Initialize message input UI
        initializeMessageInput()
    }

    override fun onResume() {
        super.onResume()

        // Mark as "open" so we don't send notifications while this is open
        repository.detailViewSubscriptionId.set(subscriptionId)

        // Update buttons (this is for when we return from the preferences screen)
        lifecycleScope.launch(Dispatchers.IO) {
            val subscription = repository.getSubscription(subscriptionId) ?: return@launch
            subscriptionInstant = subscription.instant
            subscriptionMutedUntil = subscription.mutedUntil
            subscriptionDisplayName = displayName(subscription)

            showHideInstantMenuItems(subscriptionInstant)
            showHideMutedUntilMenuItems(subscriptionMutedUntil)
            updateTitle(subscriptionDisplayName)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause hook: Removing 'notificationId' from all notifications for $subscriptionId")
        GlobalScope.launch(Dispatchers.IO) {
            // Note: This is here and not in onDestroy/onStop, because we want to clear notifications as early
            // as possible, so that we don't see the "new" bubble in the main list anymore.
            repository.clearAllNotificationIds(subscriptionId)
        }
        Log.d(TAG, "onPause hook: Marking subscription $subscriptionId as 'not open'")
        repository.detailViewSubscriptionId.set(0) // Mark as closed
    }

    private fun maybeCancelNotificationPopups(notifications: List<Notification>) {
        val notificationsWithPopups = notifications.filter { notification -> notification.notificationId != 0 }
        if (notificationsWithPopups.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                notificationsWithPopups.forEach { notification ->
                    notifier?.cancel(notification)
                    // Do NOT remove the notificationId here, we need that for the UI indicators; we'll remove it in onPause()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail_action_bar, menu)
        this.menu = menu

        // Show and hide buttons
        showHideInstantMenuItems(subscriptionInstant)
        showHideMutedUntilMenuItems(subscriptionMutedUntil)

        // Regularly check if "notification muted" time has passed
        // NOTE: This is done here, because then we know that we've initialized the menu items.
        startNotificationMutedChecker()

        return true
    }

    private fun startNotificationMutedChecker() {
        // FIXME This is awful and has to go.

        lifecycleScope.launch(Dispatchers.IO) {
            delay(1000) // Just to be sure we've initialized all the things, we wait a bit ...
            while (isActive) {
                Log.d(TAG, "Checking 'muted until' timestamp for subscription $subscriptionId")
                val subscription = repository.getSubscription(subscriptionId) ?: return@launch
                val mutedUntilExpired = subscription.mutedUntil > 1L && System.currentTimeMillis()/1000 > subscription.mutedUntil
                if (mutedUntilExpired) {
                    val newSubscription = subscription.copy(mutedUntil = 0L)
                    repository.updateSubscription(newSubscription)
                    showHideMutedUntilMenuItems(0L)
                }
                delay(60_000)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.detail_menu_test -> {
                onTestClick()
                true
            }
            R.id.detail_menu_notifications_enabled -> {
                onMutedUntilClick(enable = false)
                true
            }
            R.id.detail_menu_notifications_disabled_until -> {
                onMutedUntilClick(enable = true)
                true
            }
            R.id.detail_menu_notifications_disabled_forever -> {
                onMutedUntilClick(enable = true)
                true
            }
            R.id.detail_menu_enable_instant -> {
                onInstantEnableClick(enable = true)
                true
            }
            R.id.detail_menu_disable_instant -> {
                onInstantEnableClick(enable = false)
                true
            }
            R.id.detail_menu_copy_url -> {
                onCopyUrlClick()
                true
            }
            R.id.detail_menu_clear -> {
                onClearClick()
                true
            }
            R.id.detail_menu_settings -> {
                onSettingsClick()
                true
            }
            R.id.detail_menu_unsubscribe -> {
                onDeleteClick()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun onTestClick() {
        Log.d(TAG, "Sending test notification to ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(subscriptionBaseUrl) // May be null
                val possibleTags = listOf(
                    "warning", "skull", "success", "triangular_flag_on_post", "de",  "dog", "rotating_light", "cat", "bike", // Emojis
                    "backup", "rsync", "de-server1", "this-is-a-tag"
                )
                val priority = Random.nextInt(1, 6)
                val tags = possibleTags.shuffled().take(Random.nextInt(0, 4))
                val title = if (Random.nextBoolean()) getString(R.string.detail_test_title) else ""
                val message = getString(R.string.detail_test_message, priority)
                api.publish(subscriptionBaseUrl, subscriptionTopic, user, message, title, priority, tags, delay = "")
            } catch (e: Exception) {
                runOnUiThread {
                    val message = if (e is ApiService.UnauthorizedException) {
                        if (e.user != null) {
                            getString(R.string.detail_test_message_error_unauthorized_user, e.user.username)
                        }  else {
                            getString(R.string.detail_test_message_error_unauthorized_anon)
                        }
                    } else {
                        getString(R.string.detail_test_message_error, e.message)
                    }
                    Toast
                        .makeText(this@DetailActivity, message, Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    private fun onMutedUntilClick(enable: Boolean) {
        if (!enable) {
            Log.d(TAG, "Showing notification settings dialog for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")
            val notificationFragment = NotificationFragment()
            notificationFragment.show(supportFragmentManager, NotificationFragment.TAG)
        } else {
            Log.d(TAG, "Re-enabling notifications ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")
            onNotificationMutedUntilChanged(Repository.MUTED_UNTIL_SHOW_ALL)
        }
    }

    override fun onNotificationMutedUntilChanged(mutedUntilTimestamp: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Setting subscription 'muted until' to $mutedUntilTimestamp")
            val subscription = repository.getSubscription(subscriptionId)
            val newSubscription = subscription?.copy(mutedUntil = mutedUntilTimestamp)
            newSubscription?.let { repository.updateSubscription(newSubscription) }
            subscriptionMutedUntil = mutedUntilTimestamp
            showHideMutedUntilMenuItems(mutedUntilTimestamp)
            runOnUiThread {
                when (mutedUntilTimestamp) {
                    0L -> Toast.makeText(this@DetailActivity, getString(R.string.notification_dialog_enabled_toast_message), Toast.LENGTH_LONG).show()
                    1L -> Toast.makeText(this@DetailActivity, getString(R.string.notification_dialog_muted_forever_toast_message), Toast.LENGTH_LONG).show()
                    else -> {
                        val formattedDate = formatDateShort(mutedUntilTimestamp)
                        Toast.makeText(this@DetailActivity, getString(R.string.notification_dialog_muted_until_toast_message, formattedDate), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun onCopyUrlClick() {
        val url = topicUrl(subscriptionBaseUrl, subscriptionTopic)
        Log.d(TAG, "Copying topic URL $url to clipboard ")

        runOnUiThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("topic address", url)
            clipboard.setPrimaryClip(clip)
            Toast
                .makeText(this, getString(R.string.detail_copied_to_clipboard_message), Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun refresh() {
        Log.d(TAG, "Fetching cached notifications for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val subscription = repository.getSubscription(subscriptionId) ?: return@launch
                val user = repository.getUser(subscription.baseUrl) // May be null
                val notifications = api.poll(subscription.id, subscription.baseUrl, subscription.topic, user, subscription.lastNotificationId)
                val newNotifications = repository.onlyNewNotifications(subscriptionId, notifications)
                val toastMessage = if (newNotifications.isEmpty()) {
                    getString(R.string.refresh_message_no_results)
                } else {
                    getString(R.string.refresh_message_result, newNotifications.size)
                }
                newNotifications.forEach { notification -> repository.addNotification(notification) }
                runOnUiThread {
                    Toast.makeText(this@DetailActivity, toastMessage, Toast.LENGTH_LONG).show()
                    mainListContainer.isRefreshing = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching notifications for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}: ${e.stackTrace}", e)
                runOnUiThread {
                    Toast
                        .makeText(this@DetailActivity, getString(R.string.refresh_message_error_one, e.message), Toast.LENGTH_LONG)
                        .show()
                    mainListContainer.isRefreshing = false
                }
            }
        }
    }

    private fun onInstantEnableClick(enable: Boolean) {
        Log.d(TAG, "Toggling instant delivery setting for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        lifecycleScope.launch(Dispatchers.IO) {
            val subscription = repository.getSubscription(subscriptionId)
            val newSubscription = subscription?.copy(instant = enable)
            newSubscription?.let { repository.updateSubscription(newSubscription) }
            showHideInstantMenuItems(enable)
            runOnUiThread {
                if (enable) {
                    Toast.makeText(this@DetailActivity, getString(R.string.detail_instant_delivery_enabled), Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(this@DetailActivity, getString(R.string.detail_instant_delivery_disabled), Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun showHideInstantMenuItems(enable: Boolean) {
        if (!this::menu.isInitialized) {
            return
        }
        subscriptionInstant = enable
        runOnUiThread {
            val appBaseUrl = getString(R.string.app_base_url)
            val enableInstantItem = menu.findItem(R.id.detail_menu_enable_instant)
            val disableInstantItem = menu.findItem(R.id.detail_menu_disable_instant)
            val allowToggleInstant = BuildConfig.FIREBASE_AVAILABLE && subscriptionBaseUrl == appBaseUrl
            if (allowToggleInstant) {
                enableInstantItem?.isVisible = !subscriptionInstant
                disableInstantItem?.isVisible = subscriptionInstant
            } else {
                enableInstantItem?.isVisible = false
                disableInstantItem?.isVisible = false
            }
        }
    }

    private fun showHideMutedUntilMenuItems(mutedUntilTimestamp: Long) {
        if (!this::menu.isInitialized) {
            return
        }
        subscriptionMutedUntil = mutedUntilTimestamp
        runOnUiThread {
            val notificationsEnabledItem = menu.findItem(R.id.detail_menu_notifications_enabled)
            val notificationsDisabledUntilItem = menu.findItem(R.id.detail_menu_notifications_disabled_until)
            val notificationsDisabledForeverItem = menu.findItem(R.id.detail_menu_notifications_disabled_forever)
            notificationsEnabledItem?.isVisible = subscriptionMutedUntil == 0L
            notificationsDisabledForeverItem?.isVisible = subscriptionMutedUntil == 1L
            notificationsDisabledUntilItem?.isVisible = subscriptionMutedUntil > 1L
            if (subscriptionMutedUntil > 1L) {
                val formattedDate = formatDateShort(subscriptionMutedUntil)
                notificationsDisabledUntilItem?.title = getString(R.string.detail_menu_notifications_disabled_until, formattedDate)
            }
        }
    }

    private fun updateTitle(subscriptionDisplayName: String) {
        runOnUiThread {
            title = subscriptionDisplayName
        }
    }

    private fun onClearClick() {
        Log.d(TAG, "Clearing all notifications for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        val builder = AlertDialog.Builder(this)
        val dialog = builder
            .setMessage(R.string.detail_clear_dialog_message)
            .setPositiveButton(R.string.detail_clear_dialog_permanently_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.markAllAsDeleted(subscriptionId)
                }
            }
            .setNegativeButton(R.string.detail_clear_dialog_cancel) { _, _ -> /* Do nothing */ }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .dangerButton(this)
        }
        dialog.show()
    }

    private fun onSettingsClick() {
        Log.d(TAG, "Opening subscription settings for ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        val intent = Intent(this, DetailSettingsActivity::class.java)
        intent.putExtra(EXTRA_SUBSCRIPTION_ID, subscriptionId)
        intent.putExtra(EXTRA_SUBSCRIPTION_BASE_URL, subscriptionBaseUrl)
        intent.putExtra(EXTRA_SUBSCRIPTION_TOPIC, subscriptionTopic)
        intent.putExtra(EXTRA_SUBSCRIPTION_DISPLAY_NAME, subscriptionDisplayName)
        startActivity(intent)
    }

    private fun onDeleteClick() {
        Log.d(TAG, "Deleting subscription ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        val builder = AlertDialog.Builder(this)
        val dialog = builder
            .setMessage(R.string.detail_delete_dialog_message)
            .setPositiveButton(R.string.detail_delete_dialog_permanently_delete) { _, _ ->
                Log.d(TAG, "Deleting subscription with subscription ID $subscriptionId (topic: $subscriptionTopic)")
                GlobalScope.launch(Dispatchers.IO) {
                    repository.removeAllNotifications(subscriptionId)
                    repository.removeSubscription(subscriptionId)
                    if (subscriptionBaseUrl == appBaseUrl) {
                        messenger.unsubscribe(subscriptionTopic)
                    }
                }
                finish()
            }
            .setNegativeButton(R.string.detail_delete_dialog_cancel) { _, _ -> /* Do nothing */ }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .dangerButton(this)
        }
        dialog.show()
    }

    private fun onNotificationClick(notification: Notification) {
        if (actionMode != null) {
            handleActionModeClick(notification)
        } else if (notification.click != "") {
            try {
                startActivity(Intent(ACTION_VIEW, Uri.parse(notification.click)))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open click URL", e)
                runOnUiThread {
                    Toast
                        .makeText(this@DetailActivity, getString(R.string.detail_item_cannot_open_url, e.message), Toast.LENGTH_LONG)
                        .show()
                }
            }
        } else {
            copyToClipboard(notification)
        }
    }

    private fun copyToClipboard(notification: Notification) {
        runOnUiThread {
            copyToClipboard(this, notification)
        }
    }

    private fun onNotificationLongClick(notification: Notification) {
        if (actionMode == null) {
            beginActionMode(notification)
        }
    }

    private fun handleActionModeClick(notification: Notification) {
        adapter.toggleSelection(notification.id)
        if (adapter.selected.size == 0) {
            finishActionMode()
        } else {
            actionMode!!.title = adapter.selected.size.toString()
        }
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        this.actionMode = mode
        if (mode != null) {
            mode.menuInflater.inflate(R.menu.menu_detail_action_mode, menu)
            mode.title = "1" // One item selected
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.detail_action_mode_copy -> {
                onMultiCopyClick()
                true
            }
            R.id.detail_action_mode_delete -> {
                onMultiDeleteClick()
                true
            }
            else -> false
        }
    }

    private fun onMultiCopyClick() {
        Log.d(TAG, "Copying multiple notifications to clipboard")

        lifecycleScope.launch(Dispatchers.IO) {
            val content = adapter.selected.joinToString("\n\n") { notificationId ->
                val notification = repository.getNotification(notificationId)
                notification?.let {
                    decodeMessage(it) + "\n" + Date(it.timestamp * 1000).toString()
                }.orEmpty()
            }
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("notifications", content)
                clipboard.setPrimaryClip(clip)
                Toast
                    .makeText(this@DetailActivity, getString(R.string.detail_copied_to_clipboard_message), Toast.LENGTH_LONG)
                    .show()
                finishActionMode()
            }
        }
    }

    private fun onMultiDeleteClick() {
        Log.d(TAG, "Showing multi-delete dialog for selected items")

        val builder = AlertDialog.Builder(this)
        val dialog = builder
            .setMessage(R.string.detail_action_mode_delete_dialog_message)
            .setPositiveButton(R.string.detail_action_mode_delete_dialog_permanently_delete) { _, _ ->
                adapter.selected.map { notificationId -> viewModel.markAsDeleted(notificationId) }
                finishActionMode()
            }
            .setNegativeButton(R.string.detail_action_mode_delete_dialog_cancel) { _, _ ->
                finishActionMode()
            }
            .create()
        dialog.setOnShowListener {
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .dangerButton(this)
        }
        dialog.show()
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        endActionModeAndRedraw()
    }

    private fun beginActionMode(notification: Notification) {
        actionMode = startActionMode(this)
        adapter.toggleSelection(notification.id)

        // Fade status bar color
        val fromColor = ContextCompat.getColor(this, Colors.statusBarNormal(this))
        val toColor = ContextCompat.getColor(this, Colors.statusBarActionMode(this))
        fadeStatusBarColor(window, fromColor, toColor)
    }

    private fun finishActionMode() {
        actionMode!!.finish()
        endActionModeAndRedraw()
    }

    private fun endActionModeAndRedraw() {
        actionMode = null
        adapter.selected.clear()
        adapter.notifyItemRangeChanged(0, adapter.currentList.size)

        // Fade status bar color
        val fromColor = ContextCompat.getColor(this, Colors.statusBarActionMode(this))
        val toColor = ContextCompat.getColor(this, Colors.statusBarNormal(this))
        fadeStatusBarColor(window, fromColor, toColor)
    }

    /**
     * Initialize the message input UI components and set up event listeners
     */
    private fun initializeMessageInput() {
        // Get UI element references
        messageInputContainer = findViewById(R.id.detail_message_input_container)
        priorityLayout = findViewById(R.id.detail_priority_layout)
        priorityText = findViewById(R.id.detail_priority_text)
        titleInputLayout = findViewById(R.id.detail_title_input_layout)
        titleInput = findViewById(R.id.detail_title_input)
        tagsInputLayout = findViewById(R.id.detail_tags_input_layout)
        tagsInput = findViewById(R.id.detail_tags_input)
        attachFileButton = findViewById(R.id.detail_attach_file_button)
        markdownToggleButton = findViewById(R.id.detail_markdown_toggle_button)
        messageInputLayout = findViewById(R.id.detail_message_input_layout)
        messageInput = findViewById(R.id.detail_message_input)
        sendButton = findViewById(R.id.detail_send_button)
        attachmentInfo = findViewById(R.id.detail_attachment_info)
        attachmentInfoText = findViewById(R.id.detail_attachment_info_text)
        attachmentRemoveButton = findViewById(R.id.detail_attachment_remove)
        markdownPreview = findViewById(R.id.detail_markdown_preview)
        markdownPreviewText = findViewById(R.id.detail_markdown_preview_text)

        // Setup priority dropdown
        setupPriorityDropdown()

        // Setup click listeners
        attachFileButton.setOnClickListener { onAttachFileClick() }
        markdownToggleButton.setOnClickListener { onMarkdownToggleClick() }
        sendButton.setOnClickListener { onSendMessageClick() }
        attachmentRemoveButton.setOnClickListener { onRemoveAttachmentClick() }
        
        // Setup tags picker
        tagsInputLayout.setEndIconOnClickListener { onTagsPickerClick() }
        tagsInput.setOnClickListener { onTagsPickerClick() }
        
        // Setup IME action for send
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                onSendMessageClick()
                true
            } else {
                false
            }
        }

        // Setup text watcher for message validation and preview
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateMessage()
                if (isMarkdownMode) {
                    updateMarkdownPreview()
                }
            }
        })
    }

    /**
     * Setup the priority selection dropdown with available options
     */
    private fun setupPriorityDropdown() {
        val priorityOptions = listOf(
            getString(R.string.detail_send_priority_low) to PRIORITY_LOW,
            getString(R.string.detail_send_priority_default) to PRIORITY_DEFAULT,
            getString(R.string.detail_send_priority_high) to PRIORITY_HIGH,
            getString(R.string.detail_send_priority_max) to PRIORITY_MAX
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, priorityOptions.map { it.first })
        priorityText.setAdapter(adapter)
        
        // Set default selection
        priorityText.setText(priorityOptions.find { it.second == selectedPriority }?.first, false)

        priorityText.setOnItemClickListener { _, _, position, _ ->
            selectedPriority = priorityOptions[position].second
        }
    }

    /**
     * Handle attachment file selection click
     */
    private fun onAttachFileClick() {
        filePickerLauncher.launch("*/*")
    }

    /**
     * Handle attachment selection from file picker
     * 
     * Processes the selected file URI and displays file information.
     * Supports all file types through the generic wildcard mime type filter.
     */
    private fun onAttachmentSelected(uri: Uri) {
        try {
            val stat = fileStat(this, uri)
            selectedAttachmentUri = uri
            
            // Display file information to user
            val fileInfo = getString(R.string.detail_send_file_chosen, stat.filename, formatBytes(stat.size))
            attachmentInfoText.text = fileInfo
            attachmentInfo.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.w(TAG, "Unable to get file info for selected attachment", e)
            Toast.makeText(this, getString(R.string.detail_send_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Handle removing the selected attachment
     */
    private fun onRemoveAttachmentClick() {
        selectedAttachmentUri = null
        attachmentInfo.visibility = View.GONE
    }

    /**
     * Toggle markdown mode and preview
     */
    private fun onMarkdownToggleClick() {
        isMarkdownMode = !isMarkdownMode
        
        // Update button appearance
        markdownToggleButton.isSelected = isMarkdownMode
        
        // Show/hide markdown preview
        if (isMarkdownMode) {
            markdownPreview.visibility = View.VISIBLE
            updateMarkdownPreview()
        } else {
            markdownPreview.visibility = View.GONE
        }
    }

    /**
     * Update the markdown preview with current message content
     */
    private fun updateMarkdownPreview() {
        val message = messageInput.text?.toString() ?: ""
        if (message.isNotEmpty()) {
            // Simple markdown preview without external library
            // For now, just show the raw text with basic formatting hints
            markdownPreviewText.text = "Preview: $message"
        } else {
            markdownPreviewText.text = getString(R.string.detail_send_markdown_preview)
        }
    }

    /**
     * Validate message content and update UI state accordingly
     * 
     * This function performs real-time validation of:
     * - Message emptiness (empty messages cannot be sent)
     * - UTF-8 byte size validation against 4KB limit
     * - UI state updates (error messages, send button state)
     */
    private fun validateMessage() {
        val message = messageInput.text?.toString() ?: ""
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        val isValid = message.isNotEmpty()
        
        // Enable/disable send button based on message validity
        sendButton.isEnabled = isValid
        
        // Show error message if message exceeds 4KB limit
        if (messageBytes.size > MESSAGE_SIZE_LIMIT) {
            messageInputLayout.error = getString(R.string.detail_send_error_too_large)
        } else {
            messageInputLayout.error = null
        }
    }

    /**
     * Handle sending the message
     */
    private fun onSendMessageClick() {
        val message = messageInput.text?.toString() ?: ""
        
        if (message.isEmpty()) {
            Toast.makeText(this, getString(R.string.detail_send_error_empty), Toast.LENGTH_SHORT).show()
            return
        }

        sendMessage(message)
    }

    /**
     * Send the message with current settings and handle size validation
     * 
     * This function implements the core message sending logic with the following features:
     * - UTF-8 validation and encoding
     * - 4KB message size limit enforcement
     * - File attachment support
     * - Priority selection
     * - Robust error handling
     * 
     * If a message exceeds 4KB, it's automatically converted to a .txt file attachment
     * to ensure delivery while informing the user about the size limit.
     */
    private fun sendMessage(message: String) {
        Log.d(TAG, "Sending message to ${topicShortUrl(subscriptionBaseUrl, subscriptionTopic)}")

        // Disable send button and show loading state to prevent double-sends
        sendButton.isEnabled = false
        messageInput.isEnabled = false
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val user = repository.getUser(subscriptionBaseUrl) // May be null for anonymous publishing
                val messageBytes = message.toByteArray(Charsets.UTF_8)
                
                // Handle message size validation and file attachment logic
                val (actualMessage, filename, body) = when {
                    messageBytes.size > MESSAGE_SIZE_LIMIT -> {
                        // Message exceeds 4KB limit, send as .txt file attachment
                        val txtContent = message.toRequestBody("text/plain".toMediaTypeOrNull())
                        Triple("Message sent as .txt file due to size limit", "message.txt", txtContent)
                    }
                    selectedAttachmentUri != null -> {
                        // Regular message with user-selected file attachment
                        val stat = fileStat(this@DetailActivity, selectedAttachmentUri)
                        val body = ContentUriRequestBody(applicationContext.contentResolver, selectedAttachmentUri!!, stat.size)
                        Triple(message, stat.filename, body)
                    }
                    else -> {
                        // Regular text-only message
                        Triple(message, "", null)
                    }
                }

                // Get title and tags from input fields
                val title = titleInput.text?.toString()?.trim() ?: ""
                val tagsText = tagsInput.text?.toString()?.trim() ?: ""
                val tags = if (tagsText.isNotEmpty()) {
                    tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }

                // Publish message using ntfy API
                api.publish(
                    baseUrl = subscriptionBaseUrl,
                    topic = subscriptionTopic,
                    user = user,
                    message = actualMessage,
                    title = title,
                    priority = selectedPriority,
                    tags = tags,
                    delay = "",
                    body = body,
                    filename = filename
                )

                runOnUiThread {
                    // Reset UI state after successful send
                    resetMessageInput()
                    
                    // Show appropriate success feedback
                    val successMessage = if (messageBytes.size > MESSAGE_SIZE_LIMIT) {
                        getString(R.string.detail_send_error_too_large)
                    } else {
                        getString(R.string.detail_send_success)
                    }
                    Toast.makeText(this@DetailActivity, successMessage, 
                        if (messageBytes.size > MESSAGE_SIZE_LIMIT) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // Handle various error types with appropriate user feedback
                val errorMessage = when (e) {
                    is ApiService.UnauthorizedException -> {
                        if (e.user != null) {
                            getString(R.string.detail_test_message_error_unauthorized_user, e.user.username)
                        } else {
                            getString(R.string.detail_test_message_error_unauthorized_anon)
                        }
                    }
                    is ApiService.EntityTooLargeException -> {
                        getString(R.string.detail_test_message_error_too_large)
                    }
                    else -> {
                        getString(R.string.detail_send_error, e.message)
                    }
                }
                
                runOnUiThread {
                    // Re-enable input controls on error
                    sendButton.isEnabled = true  
                    messageInput.isEnabled = true
                    
                    Toast.makeText(this@DetailActivity, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Reset the message input UI to its initial state after sending a message
     */
    private fun resetMessageInput() {
        // Clear text inputs
        titleInput.text?.clear()
        tagsInput.text?.clear()
        messageInput.text?.clear()
        selectedTags.clear()
        
        // Remove attachment
        selectedAttachmentUri = null
        attachmentInfo.visibility = View.GONE
        
        // Reset markdown mode
        isMarkdownMode = false
        markdownToggleButton.isSelected = false
        markdownPreview.visibility = View.GONE
        
        // Re-enable input controls
        sendButton.isEnabled = true
        messageInput.isEnabled = true
    }

    /**
     * Show tags picker dialog with common tags and custom input
     */
    private fun onTagsPickerClick() {
        // Common ntfy emoji and text tags for demonstration
        val commonTags = listOf(
            "warning" to "⚠️",
            "fire" to "🔥",
            "success" to "✅",
            "error" to "❌",
            "info" to "ℹ️",
            "urgent" to "🚨",
            "skull" to "💀",
            "backup" to "💾",
            "computer" to "💻",
            "mobile" to "📱",
            "email" to "📧",
            "lock" to "🔒",
            "key" to "🔑",
            "home" to "🏠",
            "work" to "🏢",
            "construction" to "🚧",
            "checkmark" to "✓",
            "x" to "✗",
            "thumbs_up" to "👍",
            "thumbs_down" to "👎"
        )

        // Create a simple list dialog showing tag names and their emoji representations
        val tagItems = commonTags.map { "${it.first} ${it.second}" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.detail_send_tags_dialog_title))
            .setItems(tagItems) { _, which ->
                val selectedTag = commonTags[which].first
                // Add to existing tags
                val currentTags = tagsInput.text?.toString()?.trim() ?: ""
                val updatedTags = if (currentTags.isEmpty()) {
                    selectedTag
                } else {
                    "$currentTags, $selectedTag"
                }
                tagsInput.setText(updatedTags)
            }
            .setNeutralButton("Custom Tags") { _, _ ->
                showCustomTagsDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    
    /**
     * Show custom tags input dialog
     */
    private fun showCustomTagsDialog() {
        val input = TextInputEditText(this)
        input.setText(tagsInput.text)
        input.hint = getString(R.string.detail_send_tags_custom_hint)
        
        val container = LinearLayout(this)
        container.setPadding(50, 20, 50, 20)
        container.addView(input)
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.detail_send_tags_dialog_title))
            .setMessage(getString(R.string.detail_send_tags_dialog_hint))
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                tagsInput.setText(input.text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val TAG = "NtfyDetailActivity"
        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_SUBSCRIPTION_BASE_URL = "baseUrl"
        const val EXTRA_SUBSCRIPTION_TOPIC = "topic"
        const val EXTRA_SUBSCRIPTION_DISPLAY_NAME = "displayName"
        
        // Message size limit (4KB)
        const val MESSAGE_SIZE_LIMIT = 4096
    }
}
