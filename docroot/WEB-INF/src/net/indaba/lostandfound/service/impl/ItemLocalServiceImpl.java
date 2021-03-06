/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package net.indaba.lostandfound.service.impl;

import java.util.List;
import java.util.concurrent.Future;

import com.liferay.asset.kernel.model.AssetCategory;
import com.liferay.asset.kernel.model.AssetEntry;
import com.liferay.asset.kernel.model.AssetLinkConstants;
import com.liferay.asset.kernel.service.AssetCategoryLocalServiceUtil;
import com.liferay.counter.kernel.service.CounterLocalServiceUtil;
import com.liferay.message.boards.kernel.model.MBMessage;
import com.liferay.message.boards.kernel.service.MBMessageLocalServiceUtil;
import com.liferay.portal.kernel.comment.CommentManagerUtil;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.theme.ThemeDisplay;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import aQute.bnd.annotation.ProviderType;
import net.indaba.lostandfound.firebase.FirebaseService;
import net.indaba.lostandfound.firebase.FirebaseSynchronizer;
import net.indaba.lostandfound.model.Item;
import net.indaba.lostandfound.service.LFImageLocalServiceUtil;
import net.indaba.lostandfound.service.base.ItemLocalServiceBaseImpl;

/**
 * The implementation of the item local service.
 *
 * <p>
 * All custom service methods should be put in this class. Whenever methods are
 * added, rerun ServiceBuilder to copy their definitions into the
 * {@link net.indaba.lostandfound.service.ItemLocalService} interface.
 *
 * <p>
 * This is a local service. Methods of this service will not have security
 * checks based on the propagated JAAS credentials because this service can only
 * be accessed from within the same VM.
 * </p>
 *
 * @author aritz
 * @see ItemLocalServiceBaseImpl
 * @see net.indaba.lostandfound.service.ItemLocalServiceUtil
 */
@ProviderType
public class ItemLocalServiceImpl extends ItemLocalServiceBaseImpl {
	/*
	 * NOTE FOR DEVELOPERS:
	 *
	 * Never reference this class directly. Always use {@link
	 * net.indaba.lostandfound.service.ItemLocalServiceUtil} to access the item
	 * local service.
	 */

	private FirebaseService<Item> getFbService() {
		return FirebaseSynchronizer.getInstance().getService(Item.class);
	}

	private boolean updateFirebase(Item item, ServiceContext serviceContext) {
		ThemeDisplay themeDisplay = new ThemeDisplay();
		if (serviceContext != null) {
			themeDisplay = (ThemeDisplay) serviceContext.getRequest()
					.getAttribute(
							WebKeys.THEME_DISPLAY);
		}
		return (getFbService().isSyncEnabled()
				&& themeDisplay != null);
	}

	public List<Item> getItems(long groupId, int start, int end)
			throws PortalException {
		return itemPersistence.findByGroupId(groupId, start, end);
	}

	public Item addOrUpdateItem(Item item, ServiceContext serviceContext)
			throws PortalException {
		_log.debug("addOrUpdateItem");

		if (item.isNew()) {
			item.setItemId(CounterLocalServiceUtil.increment());
			item = super.addItem(item);
			CommentManagerUtil.addDiscussion(serviceContext.getUserId(),
					serviceContext.getScopeGroupId(),
					Item.class.getName(), item.getPrimaryKey(), null);
		} else {
			item = super.updateItem(item);
		}

		/* UserId needs to be set on REST API calls */
		AssetEntry assetEntry = updateAsset(serviceContext.getUserId(), item,
				serviceContext.getAssetCategoryIds(),
				serviceContext.getAssetTagNames(), serviceContext
						.getAssetLinkEntryIds(), serviceContext);
		if (updateFirebase(item, serviceContext)) {
			try {
				Future<String> firebaseKey = getFbService().addOrUpdate(item,
						null);
				List<AssetCategory> categories = AssetCategoryLocalServiceUtil
						.getAssetEntryAssetCategories(assetEntry.getEntryId());
				AssetCategory category = null;
				if (!categories.isEmpty())
					category = categories.get(0);
				FirebaseService<AssetCategory> fbCatService = FirebaseSynchronizer
						.getInstance().getService(AssetCategory.class);
				getFbService().setRelationManyToOne(item, category, fbCatService,
						firebaseKey);
			} catch (Exception e) {
				_log.error("Error updating item " + item.getItemId(), e);
			}
		}

		Indexer<Item> indexer = IndexerRegistryUtil.nullSafeGetIndexer(
				Item.class);
		indexer.reindex(item);

		return item;
	}

	public Item deleteItem(long itemId, ServiceContext serviceContext)
			throws PortalException {
		return deleteItem(itemPersistence.fetchByPrimaryKey(itemId),
				serviceContext);
	}

	public Item deleteItem(Item item, ServiceContext serviceContext)
			throws PortalException {
		try {
			AssetEntry assetEntry = assetEntryLocalService.fetchEntry(Item.class
					.getName(), item.getItemId());
			assetLinkLocalService.deleteLinks(assetEntry.getEntryId());
			assetEntryLocalService.deleteEntry(assetEntry);
		} catch (Exception e) {
			_log.error("Error deleting assetEntry");
		}

		Indexer<Item> indexer = IndexerRegistryUtil.nullSafeGetIndexer(
				Item.class);
		indexer.delete(item);

		/* Delete related messages */
		List<MBMessage> msgs = MBMessageLocalServiceUtil
				.getMessages(Item.class.getName(), item.getItemId(),
						WorkflowConstants.STATUS_ANY);
		MBMessage rootMsg = null;
		for (MBMessage m : msgs) {
			if (m.getParentMessageId() == 0) {
				rootMsg = m;
			} else {
				MBMessageLocalServiceUtil.deleteMessage(m);
			}
		}
		MBMessageLocalServiceUtil.deleteMessage(rootMsg);

		/* Delete related LFImages */
		LFImageLocalServiceUtil.deleteByItemId(item.getItemId(),
				serviceContext);
		if (updateFirebase(item, serviceContext)) {
			Future<Boolean> future = getFbService().setRelationManyToOne(item, null, FirebaseSynchronizer
					.getInstance().getService(AssetCategory.class), null);
			_log.debug("Deleting item in Firebase");
			getFbService().delete(item, future);
		}
		return super.deleteItem(item);
	}

	private AssetEntry updateAsset(long userId, Item item,
			long[] assetCategoryIds, String[] assetTagNames,
			long[] assetLinkEntryIds, ServiceContext serviceContext)
					throws PortalException {

		assetCategoryIds = assetCategoryIds == null ? new long[0]
				: assetCategoryIds;
		AssetEntry assetEntry = assetEntryLocalService.updateEntry(userId, item
				.getGroupId(), Item.class.getName(),
				item.getItemId(), assetCategoryIds, assetTagNames);
		assetLinkLocalService.updateLinks(userId, assetEntry.getEntryId(),
				assetLinkEntryIds,
				AssetLinkConstants.TYPE_RELATED);
		return assetEntry;
	}

	private final Log _log = LogFactoryUtil.getLog(this.getClass());

}