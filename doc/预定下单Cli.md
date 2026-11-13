# 途牛CLI

## 酒店预定

ps: 途牛提供的CLI, 酒店预定后不能支持CLI命令取消, 得去途牛官网手动取消

### 搜索
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564exxxxx" && tuniu call hotel tuniuHotelSearch -a '{\"cityName\":\"广州天河\",\"checkIn\":\"2026-11-12\",\"checkOut\":\"2026-11-13\"}'`

响应结果：
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "<内嵌 JSON 字符串，见下>"
      }
    ]
  },
  "metadata": {
    "server": "hotel",
    "tool": "tuniuHotelSearch",
    "latency_ms": 1116
  }
}
```

详细的 result.content[0].text 字段的内容如下：
```angular2html
{
  "displayHint": "请使用 Markdown 表格形式展示酒店列表（第 1/2 页），建议包含以下列：\n- 序号：方便用户选择\n- 酒店名称：可添加星级标识（如 ⭐⭐⭐⭐⭐）\n- 商圈位置：便于用户判断地理位置\n- 星级：如\"高档型\"\n- 品牌：如\"希尔顿\"\n- 最低价格：格式为\"¥XXX起\"，简洁展示（如\"¥1,250起\"）\n- 评分：如\"4.6分\"\n- 点评概要：如\"新街口中心，便捷出行！\"\n\n 翻页请再次调用 tuniuHotelSearch，传 queryId 与 pageNum（如 { \"queryId\": \"1487725945995520\", \"pageNum\": 2 }）。\n\n注意事项：\n1. 价格应显示为\"¥XXX起\"格式，简洁清晰\n2. 评分保留1位小数\n3. 如果有距离信息，在商圈后显示（如\"天子庙站 1.1公里\"）\n4. 表格应清晰易读，便于用户快速对比选择",
  "message": "找到 7 家酒店",
  "success": true,
  "queryId": "1487725945995520",
  "totalPageNum": 2,
  "currentPageNum": 1,
  "cityInfo": {
    "cityCode": 602,
    "cityName": "广州"
  },
  "hotels": [
    {
      "hotelId": 471907,
      "hotelName": "全季酒店（广州北京路步行街越秀公园店）",
      "starName": "高档型",
      "firstPic": "https://m.tuniucdn.com/fb3/s1/2n9c/M9q89sqHPEXfQwRxCG1w9kXU3fu_w200_h327_c1_t0.jpg",
      "address": "解放北路960号广州越天大厦",
      "cityName": "广州",
      "cityCode": 602,
      "business": "东风中路/越秀公园",
      "brandName": "全季",
      "commentScore": 5,
      "commentDigest": "出行无忧，服务暖心",
      "lowestPrice": 673,
      "meal": "无早餐",
      "refund": "限时取消",
      "roomName": "大床房",
      "roomArea": "17-22㎡",
      "roomWindow": "窗户位于走廊或过道"
    },
    {
      "hotelId": 2088893998,
      "hotelName": "桔子酒店（广州北京路步行街越秀公园店）",
      "starName": "高档型",
      "firstPic": "https://m.tuniucdn.com/fb3/s1/2n9c/AHevhg4j77P8q1HdBweSNoQr3La_w200_h327_c1_t0.jpg",
      "address": "解放北路960号越天大厦",
      "cityName": "广州",
      "cityCode": 602,
      "business": "东风中路/越秀公园",
      "brandName": "桔子酒店",
      "commentScore": 5,
      "commentDigest": "商务出行优选，尽享繁华便利",
      "lowestPrice": 754,
      "meal": "无早餐",
      "refund": "限时取消",
      "roomName": "大床房",
      "roomArea": "25-27㎡",
      "roomWindow": "无窗"
    },
    {
      "hotelId": 1882322216,
      "hotelName": "麗枫酒店（广州珠江新城地铁站店）",
      "starName": "高档型",
      "firstPic": "https://m.tuniucdn.com/fb3/s1/2n9c/4VBbYnayJqLwndzASj64LxEvKUae_w200_h327_c1_t0.jpg",
      "address": "华明路11号华普广场西塔1层（近美国领事馆）",
      "cityName": "广州",
      "cityCode": 602,
      "business": "珠江新城",
      "brandName": "麗枫",
      "commentScore": 4.9,
      "commentDigest": "珠江新城优选，舒适自在。",
      "lowestPrice": 943,
      "meal": "无早餐",
      "refund": "不可取消",
      "roomName": "标准双床房",
      "roomArea": "15-20㎡",
      "roomWindow": "无窗"
    },
    {
      "hotelId": 2056133225,
      "hotelName": "麗枫酒店（广州中山大学中山眼科中心区庄地铁站店）",
      "starName": "高档型",
      "firstPic": "https://m.tuniucdn.com/fb3/s1/2n9c/UA6WzWTEk6qR8Y37FKEWjiXB7JL_w200_h327_c1_t0.jpg",
      "address": "环市东路416号之3高迅大厦（距区庄地铁站A出口320米）",
      "cityName": "广州",
      "cityCode": 602,
      "business": "环市东路/区庄",
      "brandName": "麗枫",
      "commentScore": 4.8,
      "commentDigest": "城央绿洲，便捷无忧",
      "lowestPrice": 409,
      "meal": "无早餐",
      "refund": "不可取消",
      "roomName": "高级景观大床房",
      "roomArea": "25-26㎡",
      "roomWindow": "有窗"
    },
    {
      "hotelId": 635776,
      "hotelName": "广州正佳广场万豪酒店",
      "starName": "豪华型",
      "firstPic": "https://m.tuniucdn.com/fb3/s1/2n9c/vqbC6F4cF5WAZwd8naHecbN7jir_w200_h327_c1_t0.jpg",
      "address": "天河区天河路228号",
      "cityName": "广州",
      "cityCode": 602,
      "business": "",
      "brandName": "Marriott Hotels & Resorts",
      "commentScore": 4.8,
      "commentDigest": "中心地带，豪华享受！",
      "lowestPrice": 2503,
      "meal": "无早餐",
      "refund": "不可取消",
      "roomName": "客房, 2 张双人床",
      "roomArea": "31㎡",
      "roomWindow": "未知"
    },
    {
      "hotelId": 1896562970,
      "hotelName": "维也纳酒店（广州北京路步行街公园前地铁站店）",
      "starName": "高档型",
      "firstPic": "https://m.tuniucdn.com/fb3/s1/urcqbx/5jwdmAEqNzXBJXzu4hJrP6xzq4u_w200_h327_c1_t0.jpg",
      "address": "中山五路4号新大新百货北门1楼（近北京路）",
      "cityName": "广州",
      "cityCode": 602,
      "business": "北京路商业区",
      "brandName": "维也纳酒店",
      "commentScore": 5,
      "commentDigest": "南粤心腹地，繁华枕安眠",
      "lowestPrice": 370,
      "meal": "无早餐",
      "refund": "限时取消",
      "roomName": "高级大床房【鹅绒枕芯+舒睡床垫+私密空间】",
      "roomArea": "22㎡",
      "roomWindow": "无窗"
    },
    {
      "hotelId": 2115454611,
      "hotelName": "广州塔滨江东路亚朵酒店",
      "starName": "高档型",
      "firstPic": "https://m.tuniucdn.com/fb3/s1/urcqbx/2gd5vqLHfehohaCMcEMMMcJHp2c3_w200_h327_c1_t0.jpg",
      "address": "滨江东路795号",
      "cityName": "广州",
      "cityCode": 602,
      "business": "江南西/中大南校区",
      "brandName": "",
      "commentScore": 5,
      "commentDigest": "珠江畔，睡在云端赏广州",
      "lowestPrice": 655,
      "meal": "无早餐",
      "refund": "不可取消",
      "roomName": "尊享双床房",
      "roomArea": "35㎡",
      "roomWindow": "有窗"
    }
  ]
}
```

### 查某个酒店详情
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a55xxxxxxx" && tuniu call hotel tuniuHotelDetail -a "{\"hotelId\":471907,\"checkIn\":\"2026-11-13\",\"checkOut\":\"2026-11-14\"}"`

响应结果:
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "<内嵌 JSON 字符串，见第二节>"
      }
    ]
  },
  "metadata": {
    "server": "hotel",
    "tool": "tuniuHotelDetail",
    "latency_ms": 3652
  }
}
```

详细的 result.content[0].text 字段的内容如下：
```angular2html
{
  "displayHint": "请使用清晰的格式展示酒店详情信息，使用户可以轻松对比不同房型和价格方案：\n\n1. **基本信息**：展示酒店名称、星级、地址、商圈、评分、入住退房政策等\n2. **房型及价格**（共 5 种房型）：每种房型建议使用表格展示，包含以下信息：\n   - 房型基本信息：房型名称、面积、楼层、床型、最多入住人数\n   - 价格方案表格列：方案名称、价格、早餐、取消政策、库存、预订要求（若有）\n3. 价格格式要求：\n   - 显示为\"¥XXX\"格式（如\"¥1,250\"）\n   - 早餐信息直接显示（如\"含早\"、\"不含早\"）\n   - 不要添加多余的\"<br>\"标签或复杂格式\n4. 取消政策应简明扼要（如\"免费取消\"、\"不可取消\"）\n5. **预订要求（重要）**：若某个报价的 bookingRequirements 非空，必须把其中文案明确告知用户，并在下单前主动收集对应信息（如英文姓名、联系人邮箱），再调用 tuniuHotelCreateOrder。",
  "hotelId": 471907,
  "hotelName": "全季酒店（广州北京路步行街越秀公园店）",
  "hotelNameEn": "",
  "starName": "高档型",
  "firstPic": "https://m.tuniucdn.com/fb3/s1/urcqbx/jmsz8qmDgLiQAX9ZKtBpMPuqhkX_w180_h180_c1_t0_q70.jpg",
  "cityName": "广州",
  "cityCode": 602,
  "business": "东风中路/越秀公园",
  "commentScore": 5,
  "policies": {
    "checkInTime": "14:00",
    "checkOutTime": "12:00",
    "cancelPolicy": "入住当天17:00前可免费取消"
  },
  "roomTypes": [
    {
      "roomTypeId": "2107217451",
      "roomTypeName": "舒压-双床房",
      "bedType": "双床",
      "maxOccupancy": 2,
      "roomSize": 36,
      "floor": "3-9层",
      "images": [
        "https://m.tuniucdn.com/fb3/s1/urcqbx/jmsz8qmDgLiQAX9ZKtBpMPuqhkX_w180_h180_c1_t0_q70.jpg"
      ],
      "ratePlans": [
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "73497_32001692_0010_0002_514343567",
          "rmbPrices": "1040.0",
          "preBookParam": "2889F2ADED1DAD6E6C6969681B87AC02",
          "mealText": "无早餐",
          "cancelDesc": "如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥1040.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 2
        },
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "b581e5bdba625938bcdb95ba4b35e9c6",
          "rmbPrices": "1045.0",
          "preBookParam": "A06233EF3DBAF03C5BE5EDEEC4854547",
          "mealText": "无早餐",
          "cancelDesc": "如未入住或取消及提前离店，途牛将收取¥1044.54支付酒店。",
          "cancelText": "不可取消",
          "count": 2
        }
      ]
    },
    {
      "roomTypeId": "336612958",
      "roomTypeName": "大床房",
      "bedType": "大床",
      "maxOccupancy": 2,
      "roomSize": 17,
      "floor": "3-9层",
      "images": [
        "https://m.tuniucdn.com/fb3/s1/2n9c/2CAiXuBPkWXoju5k2G1wjRbhXRp6_w180_h180_c1_t0_q70.jpg"
      ],
      "ratePlans": [
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "5101801_DR1_Base-OTP-STD-NBF",
          "rmbPrices": "793.0",
          "preBookParam": "69065C672D33BB42267917C5C9F0A023",
          "mealText": "无早餐",
          "cancelDesc": "如果您在确认后30分钟取消，途牛不收取您任何费用。如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥793.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 0
        },
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "5101801_DR1_Base-OTP-STD-BF1",
          "rmbPrices": "833.0",
          "preBookParam": "8E3FDB5F7C53911334BC8BE990CC759C",
          "mealText": "1份早餐",
          "cancelDesc": "如果您在确认后30分钟取消，途牛不收取您任何费用。如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥833.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 0
        }
      ]
    },
    {
      "roomTypeId": "221685258",
      "roomTypeName": "高级大床房",
      "bedType": "大床",
      "maxOccupancy": 2,
      "roomSize": 18,
      "floor": "3-9层",
      "images": [
        "https://m.tuniucdn.com/fb3/s1/2n9c/3F86shPdjW5wLJ8dNnCtGsbNKEZr_w180_h180_c1_t0_q70.jpg"
      ],
      "ratePlans": [
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "5101801_SQ1_Base-OTP-STD-NBF",
          "rmbPrices": "833.0",
          "preBookParam": "FD855EB0CDFDC6964CF82F3E77CCBF59",
          "mealText": "无早餐",
          "cancelDesc": "如果您在确认后30分钟取消，途牛不收取您任何费用。如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥833.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 0
        },
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "5101801_SQ1_Base-OTP-STD-BF1",
          "rmbPrices": "873.0",
          "preBookParam": "ACEBBC51A1B159387E5D91993C588D9F",
          "mealText": "1份早餐",
          "cancelDesc": "如果您在确认后30分钟取消，途牛不收取您任何费用。如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥873.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 0
        }
      ]
    },
    {
      "roomTypeId": "2107217450",
      "roomTypeName": "舒压-高级大床房",
      "bedType": "大床",
      "maxOccupancy": 2,
      "roomSize": 48,
      "floor": "3-9层",
      "images": [
        "https://m.tuniucdn.com/fb3/s1/2n9c/2YY1oAg3ZF38BfoKLoXvFqdkkryb_w180_h180_c1_t0_q70.jpg"
      ],
      "ratePlans": [
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "5101801_SQS1_Base-OTP-STD-NBF",
          "rmbPrices": "872.0",
          "preBookParam": "3F00F5BBA5233CCE9004315B9D9D366F",
          "mealText": "无早餐",
          "cancelDesc": "如果您在确认后30分钟取消，途牛不收取您任何费用。如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥872.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 0
        },
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "5101801_SQS1_Base-OTP-STD-BF1",
          "rmbPrices": "912.0",
          "preBookParam": "A63957AEF4F5BC85A66FEF886D8BE4CC",
          "mealText": "1份早餐",
          "cancelDesc": "如果您在确认后30分钟取消，途牛不收取您任何费用。如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥912.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 0
        }
      ]
    },
    {
      "roomTypeId": "221685259",
      "roomTypeName": "双床房",
      "bedType": "双床",
      "maxOccupancy": 2,
      "roomSize": 36,
      "floor": "1-9层",
      "images": [
        "https://m.tuniucdn.com/fb3/s1/2n9c/Cq3XTASn9fYZSApfoqcxeUhCwsW_w180_h180_c1_t0_q70.jpg"
      ],
      "ratePlans": [
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "5101801_TR1_Base-OTP-STD-NBF",
          "rmbPrices": "1029.0",
          "preBookParam": "7C576D0995D3C4335005B38E7F3A68A8",
          "mealText": "无早餐",
          "cancelDesc": "如果您在确认后30分钟取消，途牛不收取您任何费用。如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥1029.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 0
        },
        {
          "ratePlanName": "标准价",
          "vendorRatePlanId": "5101801_TR1_Base-OTP-STD-BF1",
          "rmbPrices": "1069.0",
          "preBookParam": "A1C4CC7F25485E5A155B1EA695E81B8D",
          "mealText": "1份早餐",
          "cancelDesc": "如果您在确认后30分钟取消，途牛不收取您任何费用。如果您在2026-11-13 17:00之前取消，途牛不收取任何费用。如果您未如期入住、超出时限取消，途牛将收取您¥1069.0支付酒店。",
          "cancelText": "入住当天17:00前可免费取消",
          "count": 0
        }
      ]
    }
  ],
  "reviews": {
    "score": 5,
    "count": 0
  }
}
```

### 预定下单
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2axxxxxxxxx" && tuniu call hotel tuniuHotelCreateOrder -a "{\"hotelId\":\"471907\",\"roomId\":\"2107217451\",\"preBookParam\":\"4F00C886B598FA80F564911443624B19\",\"checkInDate\":\"2026-11-13\",\"checkOutDate\":\"2026-11-14\",\"roomCount\":1,\"roomGuests\":[{\"guests\":[{\"firstName\":\"红\",\"lastName\":\"小\"}]}],\"contactName\":\"小红\",\"contactPhone\":\"15079414112\"}"`

响应结果:
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "<内嵌 JSON 字符串，见第二节>"
      }
    ]
  },
  "metadata": {
    "server": "hotel",
    "tool": "tuniuHotelCreateOrder",
    "latency_ms": 8542
  }
}
```

详细的 result.content[0].text 字段的内容如下：
```angular2html
{
  "displayHint": "🎉 预订成功！\n\n请向用户展示以下订单信息：\n\n## 📋 订单信息\n- **订单号**：1268906335\n- **房间数**：1间\n- **入住人**：小红\n\n## 💳 支付链接\n**重要提示**：请使用 Markdown 链接格式展示支付链接，让用户可以直接点击跳转。\n格式示例：[点击此处完成支付](https://m.tuniu.com/hotel/orderDetail?orderId=1268906335&orderType=72)\n\n请祝贺用户预订成功！🎊",
  "success": true,
  "orderId": 1268906335,
  "paymentUrl": "https://m.tuniu.com/hotel/orderDetail?orderId=1268906335&orderType=72",
  "hotelId": "471907",
  "roomId": "2107217451",
  "checkInDate": "2026-11-13",
  "checkOutDate": "2026-11-14",
  "roomCount": 1,
  "contactName": "小红",
  "contactPhone": "15079414112"
}
```

### 取消预定
途牛提供的CLI服务暂不支持该操作

## 航班预定
### 搜索
CLI命令：`set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564xxxxx" && tuniu call flight searchLowestPriceFlight -a "{\"departureCityName\":\"北京\",\"arrivalCityName\":\"上海\",\"departureDate\":\"2026-11-20\"}"`

响应结果: 
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "<内嵌 JSON 字符串，见第二节>"
      }
    ],
    "structuredContent": {
      "result": "<与 content.text 相同的 JSON 字符串>"
    },
    "isError": false
  },
  "metadata": {
    "server": "flight",
    "tool": "searchLowestPriceFlight",
    "latency_ms": 7475
  }
}
```

详细的 result.content[0].text 字段的内容如下：
```angular2html
{
  "data": [
    {
      "airlineCompany": "厦航",
      "flightNumber": "MF8561",
      "shareFlightNo": "",
      "craftType": "738",
      "planModel": "",
      "arrivalTime": "2026-11-20 10:10",
      "arrivalAirport": "浦东",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 08:00",
      "departureAirport": "大兴",
      "departureTerminal": "",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "2h10m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G2/M00/A0/9E/Cii-TlhrTyuIGspoAAAGXFGpPfwAAGSrwP_-S4AAAbS124.png"],
      "basePrice": "490",
      "flyTime": "2h10m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "新海航｜海南航空",
      "flightNumber": "HU7601",
      "shareFlightNo": "",
      "craftType": "78B",
      "planModel": "",
      "arrivalTime": "2026-11-20 09:35",
      "arrivalAirport": "虹桥",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 07:25",
      "departureAirport": "首都",
      "departureTerminal": "T2",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "2h10m",
      "type": "直飞",
      "airComImageUrl": ["https://m.tuniucdn.com/fb2/t1/G7/M00/19/9D/Cii8Kmbeu1GIIvnmAADC43kQ4QIAAN5CgLLRuUAAML7420.png"],
      "basePrice": "500",
      "flyTime": "2h10m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "国航",
      "flightNumber": "CA1527",
      "shareFlightNo": "",
      "craftType": "73K",
      "planModel": "中",
      "arrivalTime": "2026-11-20 09:15",
      "arrivalAirport": "虹桥",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 07:00",
      "departureAirport": "首都",
      "departureTerminal": "T3",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "2h15m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G1/M00/74/55/Cii9EVZ4IsSIaMPRAAA-jXGUc2gAABGQwBqkpQAAD6l076.png"],
      "basePrice": "500",
      "flyTime": "2h15m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "东航",
      "flightNumber": "MU5186",
      "shareFlightNo": "",
      "craftType": "33J",
      "planModel": "大",
      "arrivalTime": "2026-11-20 09:45",
      "arrivalAirport": "浦东",
      "arrivalTerminal": "T1",
      "departureTime": "2026-11-20 07:30",
      "departureAirport": "大兴",
      "departureTerminal": "",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "2h15m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G1/M00/08/6D/Cii9EVbNinCINxjgAAA-wVuEaZ8AACRoQES4gIAAD7Z262.png"],
      "basePrice": "506",
      "flyTime": "2h15m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "东航",
      "flightNumber": "MU5138",
      "shareFlightNo": "",
      "craftType": "325",
      "planModel": "中",
      "arrivalTime": "2026-11-20 09:15",
      "arrivalAirport": "虹桥",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 07:20",
      "departureAirport": "大兴",
      "departureTerminal": "",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "1h55m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G1/M00/08/6D/Cii9EVbNinCINxjgAAA-wVuEaZ8AACRoQES4gIAAD7Z262.png"],
      "basePrice": "506",
      "flyTime": "1h55m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "东航",
      "flightNumber": "MU5168",
      "shareFlightNo": "",
      "craftType": "326",
      "planModel": "",
      "arrivalTime": "2026-11-20 10:15",
      "arrivalAirport": "虹桥",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 08:05",
      "departureAirport": "大兴",
      "departureTerminal": "",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "2h10m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G1/M00/08/6D/Cii9EVbNinCINxjgAAA-wVuEaZ8AACRoQES4gIAAD7Z262.png"],
      "basePrice": "552",
      "flyTime": "2h10m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "新海航｜海南航空",
      "flightNumber": "HU7603",
      "shareFlightNo": "",
      "craftType": "78A",
      "planModel": "",
      "arrivalTime": "2026-11-20 10:35",
      "arrivalAirport": "虹桥",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 08:25",
      "departureAirport": "首都",
      "departureTerminal": "T2",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "2h10m",
      "type": "直飞",
      "airComImageUrl": ["https://m.tuniucdn.com/fb2/t1/G7/M00/19/9D/Cii8Kmbeu1GIIvnmAADC43kQ4QIAAN5CgLLRuUAAML7420.png"],
      "basePrice": "600",
      "flyTime": "2h10m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "国航",
      "flightNumber": "CA1507",
      "shareFlightNo": "",
      "craftType": "773",
      "planModel": "大",
      "arrivalTime": "2026-11-20 10:00",
      "arrivalAirport": "虹桥",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 07:30",
      "departureAirport": "首都",
      "departureTerminal": "T3",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "2h30m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G1/M00/74/55/Cii9EVZ4IsSIaMPRAAA-jXGUc2gAABGQwBqkpQAAD6l076.png"],
      "basePrice": "600",
      "flyTime": "2h30m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "国航",
      "flightNumber": "CA1501",
      "shareFlightNo": "",
      "craftType": "359",
      "planModel": "",
      "arrivalTime": "2026-11-20 11:00",
      "arrivalAirport": "虹桥",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 08:30",
      "departureAirport": "首都",
      "departureTerminal": "T3",
      "cabinClass": "经济舱",
      "remainingSeats": "9",
      "totalDuration": "2h30m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G1/M00/74/55/Cii9EVZ4IsSIaMPRAAA-jXGUc2gAABGQwBqkpQAAD6l076.png"],
      "basePrice": "600",
      "flyTime": "2h30m",
      "totalTax": "190"
    },
    {
      "airlineCompany": "国航",
      "flightNumber": "CA8321",
      "shareFlightNo": "",
      "craftType": "321",
      "planModel": "",
      "arrivalTime": "2026-11-20 13:00",
      "arrivalAirport": "浦东",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 10:40",
      "departureAirport": "大兴",
      "departureTerminal": "",
      "cabinClass": "经济舱",
      "remainingSeats": "5",
      "totalDuration": "2h20m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G1/M00/74/55/Cii9EVZ4IsSIaMPRAAA-jXGUc2gAABGQwBqkpQAAD6l076.png"],
      "basePrice": "600",
      "flyTime": "2h20m",
      "totalTax": "190"
    }
  ],
  "queryId": "Y2l0eUtleXM9QkpTLVNIQSxkZXBhcnR1cmVEYXRlPTIwMjYtMTEtMjAsZGVwYXJ0dXJlRGF0ZXM9MCxwYXNzZW5nZXJRdWFudGl0eT0xIzAjMCMwLHBoeXNpY2FsQ2FiaW49bnVsbA==",
  "successCode": true,
  "totalPageNum": 1
}
```

### 查询舱位详情
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564xxxxxxxxx" && tuniu call flight multiCabinDetails -a "{\"departureCityName\":\"北京\",\"arrivalCityName\":\"上海\",\"departureDate\":\"2026-11-20\",\"flightNo\":\"MF8561\"}"`

响应结果:
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "<内嵌 JSON 字符串，见第二节>"
      }
    ],
    "structuredContent": {
      "result": "<与 content.text 相同的 JSON 字符串>"
    },
    "isError": false
  },
  "metadata": {
    "server": "flight",
    "tool": "multiCabinDetails",
    "latency_ms": 1159
  }
}
```

详细的 result.content[0].text 字段的内容如下：
```angular2html
{
  "cabinInfo": [ ... 共 32 条舱位报价 ... ],
  "flightInfo": [
    {
      "airlineCompany": "厦航",
      "punctualityRate": "90%",
      "flightNumber": "MF8561",
      "shareFlightNo": "",
      "craftType": "738",
      "planModel": "",
      "arrivalTime": "2026-11-20 10:10",
      "arrivalAirport": "浦东",
      "arrivalTerminal": "T2",
      "departureTime": "2026-11-20 08:00",
      "departureAirport": "大兴",
      "departureTerminal": "",
      "totalDuration": "2h10m",
      "type": "直飞",
      "airComImageUrl": ["http://m.tuniucdn.com/fb2/t1/G2/M00/A0/9E/Cii-TlhrTyuIGspoAAAGXFGpPfwAAGSrwP_-S4AAAbS124.png"],
      "flyTime": "2h10m"
    }
  ],
  "successCode": true
}
```

cabinInfo字段详细内容如下:
```angular2html
"cabinInfo": [
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "490",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥49起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCI2PxsVVFgIBQ=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "500",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥50起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCMMPxsVVFgICg=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "700",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥70起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCA2PxsVVFgIDQ=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "800",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥80起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0PD81J1EVVhQg"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "800",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥80起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCMcPxsVVFgICw=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "880",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥88起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0Pj81J1EVVhQi"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "880",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥88起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCM2PxsVVFgICQ=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1040",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥52起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0Oz81J1EVVhQl"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1040",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥52起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCAMPxsVVFgIDg=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1130",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥57起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0Jj81J1EVVhQ6"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1130",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥57起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCE2PxsVVFgIEQ=="
  },
  {
    "baggageInfo": "免费托运行李额30公斤",
    "basePrice": "1150",
    "buyCondition": "null",
    "cabinClass": "公务舱",
    "cabinClassCodes": "J",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥58起",
    "reimbursementProof": "行程单",
    "remainingSeats": "2",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCYMPxsVVFgIFg=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1200",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥60起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0JT81J1EVVhQ_"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1200",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥60起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCYmPxsVVFgIFA=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1290",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥65起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0JD81J1EVVhQ4"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1290",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥65起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCEcPxsVVFgIEw=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1370",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥69起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0Jz81J1EVVhQ5"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1370",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥69起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCEMPxsVVFgIEg=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1530",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥77起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0Kj81J1EVVhQ2"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1530",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥77起",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCQ2PxsVVFgIHQ=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1620",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "免费退改",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0ID81J1EVVhQ8"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1620",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "免费退改",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCYcPxsVVFgIFw=="
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1630",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "免费退改",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0Mz81J1EVVhQt"
  },
  {
    "baggageInfo": "免费托运行李额20公斤",
    "basePrice": "1630",
    "buyCondition": "null",
    "cabinClass": "经济舱",
    "cabinClassCodes": "Y",
    "mealCode": "早餐/点心",
    "refundChangeRule": "免费退改",
    "reimbursementProof": "行程单",
    "remainingSeats": "9",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCIMPxsVVFgIBg=="
  },
  {
    "baggageInfo": "免费托运行李额30公斤",
    "basePrice": "1960",
    "buyCondition": "null",
    "cabinClass": "公务舱",
    "cabinClassCodes": "J",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥98起",
    "reimbursementProof": "行程单",
    "remainingSeats": "2",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0Iz81J1EVVhQ9"
  },
  {
    "baggageInfo": "免费托运行李额30公斤",
    "basePrice": "2450",
    "buyCondition": "null",
    "cabinClass": "公务舱",
    "cabinClassCodes": "J",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥123起",
    "reimbursementProof": "行程单",
    "remainingSeats": "4",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0LD81J1EVVhQw"
  },
  {
    "baggageInfo": "免费托运行李额30公斤",
    "basePrice": "2450",
    "buyCondition": "null",
    "cabinClass": "公务舱",
    "cabinClassCodes": "J",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥123起",
    "reimbursementProof": "行程单",
    "remainingSeats": "4",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCccPxsVVFgIGw=="
  },
  {
    "baggageInfo": "免费托运行李额30公斤",
    "basePrice": "3260",
    "buyCondition": "null",
    "cabinClass": "公务舱",
    "cabinClassCodes": "J",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥163起",
    "reimbursementProof": "行程单",
    "remainingSeats": "6",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0LT81J1EVVhQ3"
  },
  {
    "baggageInfo": "免费托运行李额30公斤",
    "basePrice": "3260",
    "buyCondition": "null",
    "cabinClass": "公务舱",
    "cabinClassCodes": "J",
    "mealCode": "早餐/点心",
    "refundChangeRule": "退改¥163起",
    "reimbursementProof": "行程单",
    "remainingSeats": "6",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCQmPxsVVFgIHA=="
  },
  {
    "baggageInfo": "免费托运行李额30公斤",
    "basePrice": "8000",
    "buyCondition": "null",
    "cabinClass": "公务舱",
    "cabinClassCodes": "J",
    "mealCode": "早餐/点心",
    "refundChangeRule": "免费退改",
    "reimbursementProof": "行程单",
    "remainingSeats": "8",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OSY0Ij81J1EVVhQ-"
  },
  {
    "baggageInfo": "免费托运行李额30公斤",
    "basePrice": "8000",
    "buyCondition": "null",
    "cabinClass": "公务舱",
    "cabinClassCodes": "J",
    "mealCode": "早餐/点心",
    "refundChangeRule": "免费退改",
    "reimbursementProof": "行程单",
    "remainingSeats": "8",
    "saleControl": ["null"],
    "totalTax": "190",
    "cabinPriceId": "OQ8vBCY2PxsVVFgIFQ=="
  }
]
```

### 预定下单
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564exxxxxxx" && tuniu call flight saveOrder -a "{\"departureCityName\":\"北京\",\"arrivalCityName\":\"上海\",\"departureDate\":\"2026-11-20\",\"flightNo\":\"MF8561\",\"cabinPriceId\":\"OQ8vBCI2PxsVVFgIBQ==\",\"tourists\":[{\"name\":\"刘文灿\",\"idType\":\"身份证\",\"idNumber\":\"441802200412113817\",\"mobile\":\"15016687411\"}],\"contactTourist\":{\"name\":\"刘文灿\",\"mobile\":\"15016687411\"}}"`

响应结果: 
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": {
          "orderId": "1263516716",
          "payUrl": "https://m.tuniu.com/flight/domestic/orderDetail/1263516716?orderType=37",
          "successCode": true
        }
      }
    ],
    "structuredContent": {
      "result": {
        "orderId": "1263516716",
        "payUrl": "https://m.tuniu.com/flight/domestic/orderDetail/1263516716?orderType=37",
        "successCode": true
      }
    },
    "isError": false
  },
  "metadata": {
    "server": "flight",
    "tool": "saveOrder",
    "latency_ms": 5668
  }
}
```

### 取消预定下单
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656xxxxxx" && tuniu call flight cancelOrder -a "{\"orderId\":\"1263516716\"}"`

响应结果:
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": {
          "success": true,
          "msg": "订单取消成功！",
          "errorCode": 170000,
          "atd": null,
          "data": null
        }
      }
    ],
    "structuredContent": {
      "result": {
        "success": true,
        "msg": "订单取消成功！",
        "errorCode": 170000,
        "atd": null,
        "data": null
      }
    },
    "isError": false
  },
  "metadata": {
    "server": "flight",
    "tool": "cancelOrder",
    "latency_ms": 5531
  }
}
```

## 高铁/火车票预定
### 搜索
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564xxxxxxxxx" && tuniu call train searchLowestPriceTrain -a "{\"departureCityName\":\"南京\",\"arrivalCityName\":\"上海\",\"departureDate\":\"2026-11-20\",\"searchType\":\"5\"}"`

响应结果:
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "<内嵌 JSON 字符串，见第二节>"
      }
    ],
    "structuredContent": {
      "result": "<与 content.text 相同的 JSON 字符串>"
    },
    "isError": false
  },
  "metadata": {
    "server": "train",
    "tool": "searchLowestPriceTrain",
    "latency_ms": 1026
  }
}
```

详细的 result.content[0].text 字段的内容如下：
```angular2html
{
  "successCode": true,
  "data": [
    {
      "trainNum": "1461",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 03:04",
      "arrivalTime": "2026-11-20 06:45",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "140.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "40.5",
        "ywPrice": "94.5",
        "yzPrice": "40.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时41分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 0,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 3,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K1047",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 01:16",
      "arrivalTime": "2026-11-20 05:06",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时50分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 0,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 15,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K1555",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 01:30",
      "arrivalTime": "2026-11-20 05:12",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时42分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 1,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 99,
        "ywNum": 99,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K2185",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 01:44",
      "arrivalTime": "2026-11-20 05:33",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时49分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 3,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 17,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K359",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 01:56",
      "arrivalTime": "2026-11-20 05:22",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时26分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 0,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 10,
        "ywNum": 99,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K8481",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 02:09",
      "arrivalTime": "2026-11-20 06:03",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时54分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 0,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 0,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K151",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 02:52",
      "arrivalTime": "2026-11-20 06:39",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时47分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 0,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 3,
        "yzNum": 7,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K233",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 03:40",
      "arrivalTime": "2026-11-20 09:05",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "5时25分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 0,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K281",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 03:46",
      "arrivalTime": "2026-11-20 06:53",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时7分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 0,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 17,
        "yzNum": 6,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K849",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 03:52",
      "arrivalTime": "2026-11-20 08:48",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "4时56分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 1,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 0,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "Z195",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 07:20",
      "arrivalTime": "2026-11-20 10:02",
      "price": {
        "gjrwPrice": "261.5",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "2时42分",
      "seatAvailable": {
        "gjrwNum": 0,
        "rwNum": 13,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K737",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 07:41",
      "arrivalTime": "2026-11-20 11:34",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时53分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 8,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "T109",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 07:49",
      "arrivalTime": "2026-11-20 11:02",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时13分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 14,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K463",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 08:42",
      "arrivalTime": "2026-11-20 13:48",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "5时6分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 4,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K559",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 08:54",
      "arrivalTime": "2026-11-20 14:12",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "5时18分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 3,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 99,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "Z267",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 09:12",
      "arrivalTime": "2026-11-20 12:21",
      "price": {
        "gjrwPrice": "261.5",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时9分",
      "seatAvailable": {
        "gjrwNum": 1,
        "rwNum": 8,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 1,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "Z39",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 09:27",
      "arrivalTime": "2026-11-20 12:15",
      "price": {
        "gjrwPrice": "261.5",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "2时48分",
      "seatAvailable": {
        "gjrwNum": 0,
        "rwNum": 8,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "Z303",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 09:46",
      "arrivalTime": "2026-11-20 12:54",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时8分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 2,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "T131",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 10:00",
      "arrivalTime": "2026-11-20 13:00",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时0分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 4,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 99,
        "ywNum": 99,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "Z171",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 10:11",
      "arrivalTime": "2026-11-20 13:14",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时3分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 2,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 2,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "Z515",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 10:17",
      "arrivalTime": "2026-11-20 13:20",
      "price": {
        "gjrwPrice": "261.5",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时3分",
      "seatAvailable": {
        "gjrwNum": 0,
        "rwNum": 12,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 13,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "Z375",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 10:30",
      "arrivalTime": "2026-11-20 13:29",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "2时59分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 1,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K371",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 10:46",
      "arrivalTime": "2026-11-20 14:46",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "4时0分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 8,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 16,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K1155",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 11:02",
      "arrivalTime": "2026-11-20 14:37",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时35分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 4,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 0,
        "ywNum": 99,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K665",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 12:24",
      "arrivalTime": "2026-11-20 15:59",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时35分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 4,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 99,
        "ywNum": 99,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    },
    {
      "trainNum": "K187",
      "departStationName": "南京",
      "destStationName": "上海",
      "trainType": "direct",
      "departureTime": "2026-11-20 14:09",
      "arrivalTime": "2026-11-20 18:05",
      "price": {
        "gjrwPrice": "",
        "rwPrice": "146.5",
        "rzPrice": "",
        "swzPrice": "",
        "tdzPrice": "",
        "wzPrice": "46.5",
        "ywPrice": "100.5",
        "yzPrice": "46.5",
        "edzPrice": "",
        "ydzPrice": "",
        "dwPrice": "",
        "ydwPrice": "",
        "edwPrice": ""
      },
      "duration": "3时56分",
      "seatAvailable": {
        "gjrwNum": null,
        "rwNum": 2,
        "rzNum": null,
        "swzNum": null,
        "tdzNum": null,
        "wzNum": 5,
        "ywNum": 99,
        "yzNum": 0,
        "edzNum": null,
        "ydzNum": null,
        "dwNum": null,
        "ydwNum": null,
        "edwNum": null
      }
    }
```

响应结果:
```angular2html

```

### 查询详情
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564xxxxxxxxx" && tuniu call train searchLowestPriceTrain -a "{\"departureCityName\":\"南京\",\"arrivalCityName\":\"上海\",\"departureDate\":\"2026-11-20\",\"searchType\":\"5\"}"`

响应结果:
```angular2html
{
  "success": true,
  "result": {
    "content": [{ "type": "text", "text": "<内嵌 JSON>" }],
    "structuredContent": { "result": "<与 content.text 相同>" },
    "isError": false
  },
  "metadata": {
    "server": "train",
    "tool": "queryTrainDetail",
    "latency_ms": 554
  }
}
```

详细的 result.content[0].text 字段的内容如下：
```angular2html
{
  "successCode": true,
  "data": {
    "isVoucherSaleForDetail": true,
    "isVoucherSaleForGrab": "0",
    "trainInfo": {
      "id": "991461",
      "trainId": "991461",
      "trainNum": "1461",
      "trainType": 6,
      "trainTypeCode": 6,
      "trainTypeName": "其他",
      "departCityId": "1602",
      "destCityId": "2500",
      "departStationName": "南京",
      "departStationCode": "1175374",
      "departStationType": "过路",
      "departStationTypeCode": 2,
      "departureCityName": "南京",
      "destStationName": "上海",
      "destStationCode": "1175075",
      "destStationType": "终点",
      "destStationTypeCode": 1,
      "arrivalCityName": "上海",
      "departTime": "03:04",
      "arriveTime": "06:45",
      "saleStatus": "在售",
      "saleStatusId": 0,
      "duration": "3时41分",
      "durationDay": 1,
      "departsDate": "2026-11-20",
      "arriveDate": "2026-11-20",
      "memoDay": "",
      "memoHour": "",
      "departureDates": {
        "yesterday": "2026-11-19",
        "today": "2026-11-20",
        "tomorrow": "2026-11-21"
      },
      "leftNumber": 99,
      "seat": "硬座",
      "price": 40.5,
      "promotionPrice": 40.5,
      "resId": 99,
      "canGrap": true,
      "oneLeftNumber": 0,
      "supportSeatSelection": false,
      "supportIdCheckin": true,
      "supportTransferFlag": false
    },
    "seatInfo": [
      {
        "leftNumber": 99,
        "seatId": 8,
        "seatName": "硬座",
        "price": 40.5,
        "adultPrice": 40.5,
        "promotionPrice": 40.5,
        "resId": 354317964,
        "seatStatus": "有",
        "seatSequence": 1,
        "lowestPrice": null,
        "isConfigSeat": false,
        "configSeatInfo": null,
        "isSupportVoucherSection": true,
        "trainVoucherInfo": []
      },
      {
        "leftNumber": 3,
        "seatId": 6,
        "seatName": "硬卧",
        "price": 94.5,
        "adultPrice": 94.5,
        "promotionPrice": 94.5,
        "resId": 354317961,
        "seatStatus": "",
        "seatSequence": 5,
        "lowestPrice": 86.5,
        "isConfigSeat": false,
        "configSeatInfo": null,
        "isSupportVoucherSection": true,
        "trainVoucherInfo": []
      },
      {
        "leftNumber": 0,
        "seatId": 5,
        "seatName": "软卧",
        "price": 140.5,
        "adultPrice": 140.5,
        "promotionPrice": 140.5,
        "resId": 1718450090,
        "seatStatus": "",
        "seatSequence": 10,
        "lowestPrice": 134.5,
        "isConfigSeat": false,
        "configSeatInfo": null,
        "isSupportVoucherSection": false,
        "trainVoucherInfo": []
      },
      {
        "leftNumber": 0,
        "seatId": 9,
        "seatName": "无座",
        "price": 40.5,
        "adultPrice": 40.5,
        "promotionPrice": 40.5,
        "resId": 354317967,
        "seatStatus": "",
        "seatSequence": 13,
        "lowestPrice": null,
        "isConfigSeat": false,
        "configSeatInfo": null,
        "isSupportVoucherSection": false,
        "trainVoucherInfo": []
      }
    ],
    "isOpen": 1,
    "needAdd": false,
    "departureDates": {
      "yesterday": "2026-11-19",
      "today": "2026-11-20",
      "tomorrow": "2026-11-21"
    },
    "couponsConfig": {
      "displaySwitch": true,
      "recommendText": "用车特价券售卖"
    }
  }
}
```

### 预定下单
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564xxxxxxxxx" && tuniu call train bookTrain -a "{\"acceptStandingTicket\":false,\"adultTourists\":[{\"name\":\"刘文灿\",\"psptId\":\"441802200412113817\",\"psptType\":1,\"isStuDisabledArmyPolice\":0,\"tel\":\"15016687411\"}],\"contact\":{\"tel\":\"15016687411\"},\"resources\":[{\"resourceId\":354317964,\"adultPrice\":40.5,\"departsDate\":\"2026-11-20\"}]}"`

响应结果:
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": {
          "successCode": true,
          "data": {
            "orderId": 1260429831,
            "orderDetailUrl": "https://m.tuniu.com/m2015/train/trainTicket/trainTicketOrderDetail?orderId=1260429831&orderType=38&cFrom=0&returnFromPay=0"
          }
        }
      }
    ],
    "structuredContent": {
      "result": {
        "successCode": true,
        "data": {
          "orderId": 1260429831,
          "orderDetailUrl": "https://m.tuniu.com/m2015/train/trainTicket/trainTicketOrderDetail?orderId=1260429831&orderType=38&cFrom=0&returnFromPay=0"
        }
      }
    },
    "isError": false
  },
  "metadata": {
    "server": "train",
    "tool": "bookTrain",
    "latency_ms": 759
  }
}
```

### 取消预定下单
CLI命令: `set "TUNIU_API_KEY=sk-b5d7a4613f9646b2a656a5564xxxx" && tuniu call train cancelOrder -a "{\"orderId\":\"1260429831\"}"`

响应结果:
```angular2html
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"successCode\": true, \"data\": 0}"
      }
    ],
    "structuredContent": {
      "result": "{\"successCode\": true, \"data\": 0}"
    },
    "isError": false
  },
  "metadata": {
    "server": "train",
    "tool": "cancelOrder",
    "latency_ms": 537
  }
}
```