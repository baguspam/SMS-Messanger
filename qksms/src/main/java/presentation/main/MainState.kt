/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package presentation.main

import data.model.InboxItem
import data.model.MenuItem
import io.reactivex.Flowable

data class MainState(
        val page: MainPage = Inbox(),
        val drawerOpen: Boolean = false,
        val syncing: Boolean = false
)

sealed class MainPage

data class Inbox(
        val showClearButton: Boolean = false,
        val data: Flowable<List<InboxItem>>? = null,
        val menu: List<MenuItem> = ArrayList(),
        val showArchivedSnackbar: Boolean = false) : MainPage()

data class Archived(
        val data: Flowable<List<InboxItem>>?,
        val menu: List<MenuItem> = ArrayList()) : MainPage()

data class Scheduled(
        val data: Any? = null) : MainPage()

data class Blocked(
        val data: Any? = null) : MainPage()