/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.cassandra.db.filter;

import org.apache.cassandra.db.Cell;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.composites.CellNameType;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.DeletionInfo;

public class ColumnCounter
{
    protected int live;
    protected int ignored;
    protected final long timestamp;

    public ColumnCounter(long timestamp)
    {
        this.timestamp = timestamp;
    }

    public void count(Cell cell, DeletionInfo.InOrderTester tester)
    {
        if (!isLive(cell, tester, timestamp))
            ignored++;
        else
            live++;
    }

    protected static boolean isLive(Cell cell, DeletionInfo.InOrderTester tester, long timestamp)
    {
        return cell.isLive(timestamp) && (!tester.isDeleted(cell));
    }

    public int live()
    {
        return live;
    }

    public int ignored()
    {
        return ignored;
    }

    public ColumnCounter countAll(ColumnFamily container)
    {
        if (container == null)
            return this;

        DeletionInfo.InOrderTester tester = container.inOrderDeletionTester();
        for (Cell c : container)
            count(c, tester);
        return this;
    }

    public static class GroupByPrefix extends ColumnCounter
    {
        private final CellNameType type;
        private final int toGroup;
        private CellName last;

        /**
         * A column counter that count only 1 for all the columns sharing a
         * given prefix of the key.
         *
         * @param type the type of the column name. This can be null if {@code
         *             toGroup} is 0, otherwise it should be a composite.
         * @param toGroup the number of composite components on which to group
         *                column. If 0, all columns are grouped, otherwise we group
         *                those for which the {@code toGroup} first component are equals.
         */
        public GroupByPrefix(long timestamp, CellNameType type, int toGroup)
        {
            super(timestamp);
            this.type = type;
            this.toGroup = toGroup;

            assert toGroup == 0 || type != null;
        }

        public void count(Cell cell, DeletionInfo.InOrderTester tester)
        {
            if (!isLive(cell, tester, timestamp))
            {
                ignored++;
                return;
            }

            if (toGroup == 0)
            {
                live = 1;
                return;
            }

            CellName current = cell.name();
            assert current.size() >= toGroup;

            if (last != null) //如果复合列名与上一个一样则不计入live个数
            {
                boolean isSameGroup = true;
                for (int i = 0; i < toGroup; i++)
                {
                    //复合列名中的每个都必须一样
                    if (type.subtype(i).compare(last.get(i), current.get(i)) != 0)
                    {
                        isSameGroup = false;
                        break;
                    }
                }

                if (isSameGroup)
                    return;
            }

            live++;
            last = current;
        }
    }
}
