/*
 * CollectionDAO.java
 *
 * Version: $Revision: 1727 $
 *
 * Date: $Date: 2007-01-19 10:52:10 +0000 (Fri, 19 Jan 2007) $
 *
 * Copyright (c) 2002-2005, Hewlett-Packard Company and Massachusetts
 * Institute of Technology.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * - Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Neither the name of the Hewlett-Packard Company nor the name of the
 * Massachusetts Institute of Technology nor the names of their
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */
package org.dspace.content.dao;

import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.ItemIterator;
import org.dspace.content.WorkspaceItem;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.LogManager;
import org.dspace.eperson.Group;
import org.dspace.event.Event;
import org.dspace.uri.ObjectIdentifierService;
import org.dspace.uri.*;
import org.dspace.uri.dao.ObjectIdentifierStorageException;
import org.dspace.uri.dao.ExternalIdentifierStorageException;
import org.dspace.workflow.WorkflowItem;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author James Rutherford
 * @author Richard Jones
 */
public class CollectionDAOCore extends CollectionDAO
{
    public CollectionDAOCore(Context context)
    {
        super(context);
    }

    @Override
    public Collection create() throws AuthorizeException
    {
        try
        {
            Collection collection = childDAO.create();

            // now assign an object identifier
            ObjectIdentifier oid = ObjectIdentifierService.mint(context, collection);

            // now assign any required external identifiers
            List<ExternalIdentifier> eids = ExternalIdentifierService.mintAll(context, collection);
            collection.setExternalIdentifiers(eids);

            // create the default authorization policy for collections
            // of 'anonymous' READ
            Group anonymousGroup = groupDAO.retrieve(0);

            int actions[] = {
                Constants.READ,
                Constants.DEFAULT_ITEM_READ,
                Constants.DEFAULT_BITSTREAM_READ
            };

            for (int action : actions)
            {
                ResourcePolicy policy = ResourcePolicy.create(context);
                policy.setResource(collection);
                policy.setAction(action);
                policy.setGroup(anonymousGroup);
                policy.update();
            }

            update(collection);

            log.info(LogManager.getHeader(context, "create_collection",
                    "collection_id=" + collection.getID())
                    + ",uri=" +
                    collection.getIdentifier().getCanonicalForm());

            return collection;
        }
        catch (UnsupportedIdentifierException e)
        {
            throw new RuntimeException(e);
        }
        catch (IdentifierException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Collection retrieve(int id)
    {
        Collection collection =
                (Collection) context.fromCache(Collection.class, id);

        if (collection == null)
        {
            collection = childDAO.retrieve(id);
        }

        return collection;
    }

    @Override
    public void update(Collection collection) throws AuthorizeException
    {
        try
        {
            // Check authorisation
            collection.canEdit();

            log.info(LogManager.getHeader(context, "update_collection",
                    "collection_id=" + collection.getID()));

            ItemIterator iterator = collection.getItems();

            while (iterator.hasNext())
            {
                Item item = iterator.next();
                link(collection, item); // create mapping row in the db
                itemDAO.update(item);   // save changes to item
            }

            // finally, deal with the item identifier/uuid
            ObjectIdentifier oid = collection.getIdentifier();
            if (oid == null)
            {
                /*
                oid = new ObjectIdentifier(true);
                collection.setIdentifier(oid);*/
                oid = ObjectIdentifierService.mint(context, collection);
            }
            oidDAO.update(collection.getIdentifier());

            // deal with the external identifiers
            List<ExternalIdentifier> eids = collection.getExternalIdentifiers();
            for (ExternalIdentifier eid : eids)
            {
                identifierDAO.update(eid);
            }

            childDAO.update(collection);
        }
        catch (SQLException e)
        {
            log.error("caught exception: ", e);
            throw new RuntimeException(e);
        }
        catch (ObjectIdentifierStorageException e)
        {
            log.error("caught exception: ", e);
            throw new RuntimeException(e);
        }
        catch (ExternalIdentifierStorageException e)
        {
            log.error("caught exception: ", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * FIXME: migrate to using DAOs.
     */
    @Override
    public void delete(int id) throws AuthorizeException
    {
        try
        {
            Collection collection = retrieve(id);

            log.info(LogManager.getHeader(context, "delete_collection",
                    "collection_id=" + id));

            context.removeCached(collection, id);

            // Remove Template Item
            collection.removeTemplateItem();
            
            // Remove items
            for (Item item : itemDAO.getItemsByCollection(collection))
            {
                if (item.isOwningCollection(collection))
                {
                    // the collection to be deleted is the owning collection,
                    // thus remove the item from all collections it belongs to
                    for (Collection c : getParentCollections(item))
                    {
                        // Move the item out of all parent collections
                        unlink(c, item);
                    }
                } 
                else
                {
                    // the item was only mapped to this collection, so just
                    // remove it
                    unlink(collection, item);
                }
            }

            // Delete bitstream logo
            collection.setLogo(null);

            // Remove all authorization policies
            AuthorizeManager.removeAllPolicies(context, collection);

            // Remove any WorkflowItems
            for (WorkflowItem wfi :
                    WorkflowItem.findByCollection(context, collection))
            {
                // remove the workflowitem first, then the item
                wfi.deleteWrapper();
                itemDAO.delete(wfi.getItem().getID());
            }

            // Remove any WorkspaceItems
            for (WorkspaceItem wsi :
                    WorkspaceItem.findByCollection(context, collection))
            {
                wsi.deleteAll();
            }

            // Remove any associated groups - must happen after deleting
            List<Group> groups = new ArrayList<Group>();
            for (Group g : collection.getWorkflowGroups())
            {
                groups.add(g);
            }
            groups.add(collection.getAdministrators());
            groups.add(collection.getSubmitters());

            for (Group g : groups)
            {
                if (g != null)
                {
                    g.delete();
                }
            }

            // remove the object identifier
            oidDAO.delete(collection.getIdentifier());
        }
        catch (ObjectIdentifierStorageException e)
        {
            log.error("caught exception: ", e);
            throw new RuntimeException(e);
        }
        catch (IOException ioe)
        {
            throw new RuntimeException(ioe);
        }

        childDAO.delete(id);
    }

    @Override
    public List<Collection> getCollectionsByAuthority(Community parent,
            int actionID)
    {
        List<Collection> results = new ArrayList<Collection>();
        List<Collection> collections = null;

        if (parent != null)
        {
            collections = getChildCollections(parent);
        }
        else
        {
            collections = getCollections();
        }

        for (Collection collection : collections)
        {
            if (AuthorizeManager.authorizeActionBoolean(context,
                    collection, actionID))
            {
                results.add(collection);
            }
        }

        return results;
    }

    @Override
    public List<Collection> getCollectionsNotLinked(Item item)
    {
        List<Collection> collections = new ArrayList<Collection>();

        for (Collection collection : getCollections())
        {
            if (!linked(collection, item))
            {
                collections.add(collection);
            }
        }
        
        return collections;
    }

    @Override
    public void link(Collection collection, Item item)
        throws AuthorizeException
    {
        AuthorizeManager.authorizeAction(context, collection,
                Constants.ADD);

        log.info(LogManager.getHeader(context, "add_item",
                    "collection_id=" + collection.getID() +
                    ",item_id=" + item.getID()));

        context.addEvent(new Event(Event.MODIFY, Constants.COLLECTION,
                collection.getID(), null));
        context.addEvent(new Event(Event.MODIFY, Constants.ITEM,
                item.getID(), null));

        // If we're adding the Item to the Collection, we bequeath the
        // policies unto it.
        AuthorizeManager.inheritPolicies(context, collection, item);

        childDAO.link(collection, item);
    }

    @Override
    public void unlink(Collection collection, Item item)
        throws AuthorizeException
    {
        AuthorizeManager.authorizeAction(context, collection,
                Constants.REMOVE);

        log.info(LogManager.getHeader(context, "remove_item",
                "collection_id=" + collection.getID() + 
                ",item_id=" + item.getID()));

        context.addEvent(new Event(Event.MODIFY, Constants.COLLECTION,
                collection.getID(), null));
        context.addEvent(new Event(Event.MODIFY, Constants.ITEM,
                item.getID(), null));

        childDAO.unlink(collection, item);

        if (getParentCollections(item).size() == 0)
        {
            // make the right to remove the item explicit because the implicit
            // relation has been removed. This only has to concern the
            // currentUser because he started the removal process and he will
            // end it too. also add right to remove from the item to remove
            // it's bundles.
            AuthorizeManager.addPolicy(context, item, Constants.DELETE,
                    context.getCurrentUser());
            AuthorizeManager.addPolicy(context, item, Constants.REMOVE,
                    context.getCurrentUser());

            itemDAO.delete(item.getID());
        }
    }
}
