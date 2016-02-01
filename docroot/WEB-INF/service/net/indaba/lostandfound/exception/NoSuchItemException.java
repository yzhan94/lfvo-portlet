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

package net.indaba.lostandfound.exception;

import com.liferay.portal.exception.NoSuchModelException;

import aQute.bnd.annotation.ProviderType;


/**
 * @author aritz
 */
@ProviderType
public class NoSuchItemException extends NoSuchModelException {

	public NoSuchItemException() {
	}

	public NoSuchItemException(String msg) {
		super(msg);
	}

	public NoSuchItemException(String msg, Throwable cause) {
		super(msg, cause);
	}

	public NoSuchItemException(Throwable cause) {
		super(cause);
	}

}