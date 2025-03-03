package com.simplemobiletools.dialer.activities

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.viewpager.widget.ViewPager
import com.google.android.material.snackbar.Snackbar
//import com.obss.textlogger.TextLogger
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.commons.models.SimpleContact
import com.simplemobiletools.dialer.BuildConfig
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.adapters.ViewPagerAdapter
import com.simplemobiletools.dialer.dialogs.ChangeSortingDialog
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.extensions.launchCreateNewContactIntent
import com.simplemobiletools.dialer.fragments.FavoritesFragment
import com.simplemobiletools.dialer.fragments.MyViewPagerFragment
import com.simplemobiletools.dialer.helpers.OPEN_DIAL_PAD_AT_LAUNCH
import com.simplemobiletools.dialer.helpers.RecentsHelper
import com.simplemobiletools.dialer.helpers.tabsList
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_contacts.*
import kotlinx.android.synthetic.main.fragment_favorites.*
import kotlinx.android.synthetic.main.fragment_recents.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.grantland.widget.AutofitHelper
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : SimpleActivity() {
    private var launchedDialer = false
    private var storedShowTabs = 0
    var cachedContacts = ArrayList<SimpleContact>()

    override fun onCreate(savedInstanceState: Bundle?)
    {
        Log.v("MAIN", "onCreate()" )

        isMaterialActivity = true
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()
        updateMaterialActivityViews(main_coordinator, main_holder, useTransparentNavigation = false, useTopSearchMenu = true)

        launchedDialer = savedInstanceState?.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH) ?: false

        if (isDefaultDialer()) {
            checkContactPermissions()

            ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            0
            )

            if (!config.wasOverlaySnackbarConfirmed && !Settings.canDrawOverlays(this)) {
                val snackbar = Snackbar.make(main_holder, R.string.allow_displaying_over_other_apps, Snackbar.LENGTH_INDEFINITE).setAction(R.string.ok) {
                    config.wasOverlaySnackbarConfirmed = true
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }

                snackbar.setBackgroundTint(getProperBackgroundColor().darkenColor())
                snackbar.setTextColor(getProperTextColor())
                snackbar.setActionTextColor(getProperTextColor())
                snackbar.show()
            }

            handleNotificationPermission { granted ->
                if (!granted) {
                    toast(R.string.no_post_notifications_permissions)
                }
            }
        } else {
            launchSetDefaultDialerIntent()
        }

        if (isQPlus() && config.blockUnknownNumbers) {
            setDefaultCallerIdApp()
        }

        setupTabs()
        SimpleContact.sorting = config.sorting

        //if (BuildConfig.DEBUG) {
//            val textLogger = TextLogger(this)
//
//            textLogger.init()
        //}

        //saveLog()
        
        saveLogsToTxtFile()

    }

    override fun onResume() {
        super.onResume()

        Log.v("MAIN", "onResume()" )

        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        updateMenuColors()
        val properPrimaryColor = getProperPrimaryColor()
        val dialpadIcon = resources.getColoredDrawableWithColor(R.drawable.ic_dialpad_vector, properPrimaryColor.getContrastColor())
        main_dialpad_button.setImageDrawable(dialpadIcon)

        updateTextColors(main_holder)
        setupTabColors()

        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), getProperPrimaryColor(), getProperPrimaryColor())
        }

        if (!main_menu.isSearchOpen) {
            refreshItems(true)
        }

        checkShortcuts()
        Handler().postDelayed({
            recents_fragment?.refreshItems()
        }, 2000)

        //saveLog()

        saveLogsToTxtFile()

    }

    override fun onPause() {

        Log.v("MAIN", "onPause()" )

        super.onPause()
        storedShowTabs = config.showTabs
        config.lastUsedViewPagerPage = view_pager.currentItem
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        Log.v("MAIN", "onActivityResult()" )

        // we don't really care about the result, the app can work without being the default Dialer too
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER) {
            checkContactPermissions()
        } else if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK) {
            toast(R.string.must_make_default_caller_id_app, length = Toast.LENGTH_LONG)
            baseConfig.blockUnknownNumbers = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        Log.v("MAIN", "onSaveInstanceState()" )

        outState.putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, launchedDialer)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        Log.v("MAIN", "onConfigurationChanged()" )

        refreshItems()
    }

    override fun onBackPressed() {

        Log.v("MAIN", "onBackPressed()" )

        if (main_menu.isSearchOpen) {
            main_menu.closeSearch()
        } else {
            super.onBackPressed()
        }
    }

    private fun refreshMenuItems() {

        Log.v("MAIN", "refreshMenuItems()" )

        val currentFragment = getCurrentFragment()
        main_menu.getToolbar().menu.apply {
            findItem(R.id.clear_call_history).isVisible = currentFragment == recents_fragment
            findItem(R.id.sort).isVisible = currentFragment != recents_fragment
            findItem(R.id.create_new_contact).isVisible = currentFragment == contacts_fragment
            findItem(R.id.more_apps_from_us).isVisible = !resources.getBoolean(R.bool.hide_google_relations)
        }
    }

    private fun setupOptionsMenu() {

        Log.v("MAIN", "setupOptionsMenu()" )

        main_menu.getToolbar().inflateMenu(R.menu.menu)
        main_menu.toggleHideOnScroll(false)
        main_menu.setupMenu()

        main_menu.onSearchClosedListener = {
            getAllFragments().forEach {
                it?.onSearchQueryChanged("")
            }
        }

        main_menu.onSearchTextChangedListener = { text ->
            getCurrentFragment()?.onSearchQueryChanged(text)
        }

        main_menu.getToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.clear_call_history -> clearCallHistory()
                R.id.create_new_contact -> launchCreateNewContactIntent()
                R.id.sort -> showSortingDialog(showCustomSorting = getCurrentFragment() is FavoritesFragment)
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {

        Log.v("MAIN", "updateMenuColors()" )

        updateStatusbarColor(getProperBackgroundColor())
        main_menu.updateColors()
    }

    private fun checkContactPermissions() {

        Log.v("MAIN", "checkContactPermissions()" )

        handlePermission(PERMISSION_READ_CONTACTS) {
            initFragments()
        }

    }

    private fun clearCallHistory() {

        Log.v("MAIN", "clearCallHistory()" )

        val confirmationText = "${getString(R.string.remove_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(this, confirmationText) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                runOnUiThread {
                    recents_fragment?.refreshItems()
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun  checkShortcuts() {

        Log.v("MAIN", "checkShortcuts()" )

        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val launchDialpad = getLaunchDialpadShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = listOf(launchDialpad)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getLaunchDialpadShortcut(appIconColor: Int): ShortcutInfo {

        Log.v("MAIN", "getLaunchDialpadShortcut()" )

        val newEvent = getString(R.string.dialpad)
        val drawable = resources.getDrawable(R.drawable.shortcut_dialpad)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, DialpadActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "launch_dialpad")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun setupTabColors() {

        Log.v("MAIN", "setupTabColors()" )

        val activeView = main_tabs_holder.getTabAt(view_pager.currentItem)?.customView
        updateBottomTabItemColors(activeView, true, getSelectedTabDrawableIds()[view_pager.currentItem])

        getInactiveTabIndexes(view_pager.currentItem).forEach { index ->
            val inactiveView = main_tabs_holder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
        }

        val bottomBarColor = getBottomNavigationBackgroundColor()
        main_tabs_holder.setBackgroundColor(bottomBarColor)
        updateNavigationBarColor(bottomBarColor)
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until main_tabs_holder.tabCount).filter { it != activeIndex }

    private fun getSelectedTabDrawableIds(): ArrayList<Int> {

        Log.v("MAIN", "getSelectedTabDrawableIds()" )

        val showTabs = config.showTabs
        val icons = ArrayList<Int>()

        if (showTabs and TAB_CONTACTS != 0) {
            icons.add(R.drawable.ic_person_vector)
        }

        if (showTabs and TAB_FAVORITES != 0) {
            icons.add(R.drawable.ic_star_vector)
        }

        if (showTabs and TAB_CALL_HISTORY != 0) {
            icons.add(R.drawable.ic_clock_filled_vector)
        }

        return icons
    }

    private fun getDeselectedTabDrawableIds(): ArrayList<Int> {

        Log.v("MAIN", "getDeselectedTabDrawableIds()" )

        val showTabs = config.showTabs
        val icons = ArrayList<Int>()

        if (showTabs and TAB_CONTACTS != 0) {
            icons.add(R.drawable.ic_person_outline_vector)
        }

        if (showTabs and TAB_FAVORITES != 0) {
            icons.add(R.drawable.ic_star_outline_vector)
        }

        if (showTabs and TAB_CALL_HISTORY != 0) {
            icons.add(R.drawable.ic_clock_vector)
        }

        return icons
    }

    private fun initFragments() {

        Log.v("MAIN", "initFragments()" )

        view_pager.offscreenPageLimit = 2
        view_pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {

                Log.v("MAIN", "onPageSelected()" )

                main_tabs_holder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
                refreshMenuItems()
            }
        })

        // selecting the proper tab sometimes glitches, add an extra selector to make sure we have it right
        main_tabs_holder.onGlobalLayout {

            Handler().postDelayed({
                var wantedTab = getDefaultTab()

                // open the Recents tab if we got here by clicking a missed call notification
                if (intent.action == Intent.ACTION_VIEW && config.showTabs and TAB_CALL_HISTORY > 0) {
                    wantedTab = main_tabs_holder.tabCount - 1

                    ensureBackgroundThread {
                        clearMissedCalls()
                    }
                }

                main_tabs_holder.getTabAt(wantedTab)?.select()
                refreshMenuItems()
            }, 100L)
        }

        main_dialpad_button.setOnClickListener {
            launchDialpad()
        }

        view_pager.onGlobalLayout {
            refreshMenuItems()
        }

        if (config.openDialPadAtLaunch && !launchedDialer) {
            launchDialpad()
            launchedDialer = true
        }
    }

    private fun setupTabs() {

        Log.v("MAIN", "setupTabs()" )

        view_pager.adapter = null
        main_tabs_holder.removeAllTabs()
        tabsList.forEachIndexed { index, value ->
            if (config.showTabs and value != 0) {
                main_tabs_holder.newTab().setCustomView(R.layout.bottom_tablayout_item).apply {
                    customView?.findViewById<ImageView>(R.id.tab_item_icon)?.setImageDrawable(getTabIcon(index))
                    customView?.findViewById<TextView>(R.id.tab_item_label)?.text = getTabLabel(index)
                    AutofitHelper.create(customView?.findViewById(R.id.tab_item_label))
                    main_tabs_holder.addTab(this)
                }
            }
        }

        main_tabs_holder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(it.customView, false, getDeselectedTabDrawableIds()[it.position])
            },
            tabSelectedAction = {
                main_menu.closeSearch()
                view_pager.currentItem = it.position
                updateBottomTabItemColors(it.customView, true, getSelectedTabDrawableIds()[it.position])
            }
        )

        main_tabs_holder.beGoneIf(main_tabs_holder.tabCount == 1)
        storedShowTabs = config.showTabs
    }

    private fun getTabIcon(position: Int): Drawable {

        Log.v("MAIN", "getTabIcon()" )

        val drawableId = when (position) {
            0 -> R.drawable.ic_person_vector
            1 -> R.drawable.ic_star_vector
            else -> R.drawable.ic_clock_vector
        }

        return resources.getColoredDrawableWithColor(drawableId, getProperTextColor())
    }

    private fun getTabLabel(position: Int): String {

        Log.v("MAIN", "getTabLabel()" )

        val stringId = when (position) {
            0 -> R.string.contacts_tab
            1 -> R.string.favorites_tab
            else -> R.string.call_history_tab
        }

        return resources.getString(stringId)
    }

    private fun refreshItems(openLastTab: Boolean = false) {

        Log.v("MAIN", "refreshItems()" )

        if (isDestroyed || isFinishing) {
            return
        }

        if (view_pager.adapter == null) {
            view_pager.adapter = ViewPagerAdapter(this)
            view_pager.currentItem = if (openLastTab) config.lastUsedViewPagerPage else getDefaultTab()
            view_pager.onGlobalLayout {
                refreshFragments()
            }
        } else {
            refreshFragments()
        }
    }

    private fun launchDialpad() {

        Log.v("MAIN", "launchDialpad()" )

        Intent(applicationContext, DialpadActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun refreshFragments() {

        Log.v("MAIN", "refreshFragments()" )

        contacts_fragment?.refreshItems()
        favorites_fragment?.refreshItems()
        recents_fragment?.refreshItems()
    }

    private fun getAllFragments(): ArrayList<MyViewPagerFragment?> {

        Log.v("MAIN", "getAllFragments()" )

        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment?>()

        if (showTabs and TAB_CONTACTS > 0) {
            fragments.add(contacts_fragment)
        }

        if (showTabs and TAB_FAVORITES > 0) {
            fragments.add(favorites_fragment)
        }

        if (showTabs and TAB_CALL_HISTORY > 0) {
            fragments.add(recents_fragment)
        }

        return fragments
    }

    private fun getCurrentFragment(): MyViewPagerFragment? = getAllFragments().getOrNull(view_pager.currentItem)

    private fun getDefaultTab(): Int {

        Log.v("MAIN", "getDefaultTab()" )

        val showTabsMask = config.showTabs
        return when (config.defaultTab) {
            TAB_LAST_USED -> if (config.lastUsedViewPagerPage < main_tabs_holder.tabCount) config.lastUsedViewPagerPage else 0
            TAB_CONTACTS -> 0
            TAB_FAVORITES -> if (showTabsMask and TAB_CONTACTS > 0) 1 else 0
            else -> {
                if (showTabsMask and TAB_CALL_HISTORY > 0) {
                    if (showTabsMask and TAB_CONTACTS > 0) {
                        if (showTabsMask and TAB_FAVORITES > 0) {
                            2
                        } else {
                            1
                        }
                    } else {
                        if (showTabsMask and TAB_FAVORITES > 0) {
                            1
                        } else {
                            0
                        }
                    }
                } else {
                    0
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearMissedCalls() {

        Log.v("MAIN", "clearMissedCalls()" )

        try {
            // notification cancellation triggers MissedCallNotifier.clearMissedCalls() which, in turn,
            // should update the database and reset the cached missed call count in MissedCallNotifier.java
            // https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/ui/MissedCallNotifierImpl.java#170
            telecomManager.cancelMissedCallsNotification()
        } catch (ignored: Exception) {
        }
    }

    private fun launchSettings() {

        Log.v("MAIN", "launchSettings()" )

        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {

        Log.v("MAIN", "launchAbout()" )

        val licenses = LICENSE_GLIDE or LICENSE_INDICATOR_FAST_SCROLL or LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_1_title, R.string.faq_1_text),
            FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
        )

        if (!resources.getBoolean(R.bool.hide_google_relations)) {
            faqItems.add(FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons))
            faqItems.add(FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons))
        }

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {

        Log.v("MAIN", "showSortingDialog()" )

        ChangeSortingDialog(this, showCustomSorting) {
            favorites_fragment?.refreshItems {
                if (main_menu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(main_menu.getCurrentQuery())
                }
            }

            contacts_fragment?.refreshItems {
                if (main_menu.isSearchOpen) {
                    getCurrentFragment()?.onSearchQueryChanged(main_menu.getCurrentQuery())
                }
            }
        }
    }

    fun cacheContacts(contacts: List<SimpleContact>) {

        Log.v("MAIN", "cacheContacts()" )

        try {
            cachedContacts.clear()
            cachedContacts.addAll(contacts)
        } catch (e: Exception) {
        }
    }

    private fun saveLog() : StringBuilder{

        Log.v("MAIN", "saveLog()" )

        val stringBuilderLog = StringBuilder()

        // 1) "logcat -d" -> Default Logcat behaviour
        // 2) "logcat -d *:V" -> Verbose
        // 3) "logcat -d *:D" -> Debug
        // 4) "logcat -d *:I" -> Info
        // 5) "logcat -d *:W" -> Warn
        // 6) "logcat -d *:E" -> Error

        //In my case i needed to print error logs to txt file.

        val command = "logcat -d *:V"
        val process = Runtime.getRuntime().exec(command)
        val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (bufferedReader.readLine().also { line = it } != null) {
            stringBuilderLog.append(line).append("\n")
        }
        return stringBuilderLog
    }

     private fun saveLogsToTxtFile() {

         Log.v("MAIN", "saveLogsToTxtFile()" )

        /* Using coroutines for thread management, you can use other thread management ways as well ,
        but I highly recommend to use this thread management methods in asynchronous way because of performance reasons. */
        val coroutineCallLogger = CoroutineScope(Dispatchers.IO)
        coroutineCallLogger.launch {
            async {
                //Internal storage directory reference from context.
                val fileDirectory = this@MainActivity.filesDir
                //Creating file you can change TEXT_LOGGER whatever your name but you need to give .txt at the end of name for creating txt file.
                val filePath = File(fileDirectory, "TEXT_LOGGER.txt")
                //Checking if file exist and delete the same named file exist because it will append new logs into old logs.
                if (filePath.exists()) {
                    filePath.delete()
                }
                // This run block is coroutine usage purposes.
                runCatching {
                    //Creating new txt file.
                    filePath.createNewFile()
                    //Writing my logs to txt file.
                    filePath.appendText(
                        saveLog().toString()
                    )
                }
            }
        }
    }
    
}
