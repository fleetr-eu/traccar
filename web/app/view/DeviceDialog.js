/*
 * Copyright 2015 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

Ext.define('Traccar.view.DeviceDialog', {
    extend: 'Traccar.view.BaseEditDialog',

    requires: [
        'Traccar.view.BaseEditDialogController'
    ],

    controller: 'baseEditDialog',
    title: Strings.deviceDialog,

    items: {
        xtype: 'form',
        items: [{
            xtype: 'textfield',
            name: 'name',
            fieldLabel: Strings.sharedName,
            allowBlank: false
        }, {
            xtype: 'textfield',
            name: 'uniqueId',
            fieldLabel: Strings.deviceIdentifier,
            allowBlank: false
        }, {
            xtype: 'combobox',
            name: 'groupId',
            fieldLabel: Strings.groupParent,
            store: 'Groups',
            queryMode: 'local',
            displayField: 'name',
            valueField: 'id'
        }, {
            xtype: 'textfield',
            name: 'odometer',
            fieldLabel: Strings.odometer,
            allowBlank: false
        	}
        ]
    }
});
