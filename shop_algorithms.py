from copy import deepcopy
import json

MAX_LINES = 100
MAX_QUANTITY = 999
MAX_PRICE = 1_000_000_000


def _positive_int(value, name, maximum=2**63 - 1):
    """Вспомогательная проверка: bool не считается количеством или ценой."""
    if type(value) is not int or not 1 <= value <= maximum:
        raise ValueError(f'{name}: требуется целое число от 1 до {maximum}')
    return value


def _normalize_request(player_id, lines, currency):
    """Проверить контракт и привести корзину к единому порядку."""
    _positive_int(player_id, 'player_id')
    if currency != 'COIN':
        raise ValueError('Поддерживается только внутренняя валюта COIN')
    if not isinstance(lines, list) or not 1 <= len(lines) <= MAX_LINES:
        raise ValueError(f'Корзина должна содержать 1..{MAX_LINES} строк')
    normalized, seen = [], set()
    for line in lines:
        if not isinstance(line, dict) or set(line) != {
            'offer_id', 'quantity', 'expected_unit_price'
        }:
            raise ValueError('Нужны поля offer_id, quantity, expected_unit_price')
        offer_id = _positive_int(line['offer_id'], 'offer_id')
        quantity = _positive_int(line['quantity'], 'quantity', MAX_QUANTITY)
        expected = _positive_int(line['expected_unit_price'],
                                 'expected_unit_price', MAX_PRICE)
        if offer_id in seen:
            raise ValueError('Объедините одинаковые предложения в одну строку')
        seen.add(offer_id)
        normalized.append({'offer_id': offer_id, 'quantity': quantity,
                           'expected_unit_price': expected})
    normalized.sort(key=lambda row: row['offer_id'])
    return {'player_id': player_id, 'currency': currency, 'lines': normalized}


def calculate_order_total(lines):
    if not isinstance(lines, list) or not 1 <= len(lines) <= MAX_LINES:
        raise ValueError('Пустая или слишком большая корзина')
    total, priced_lines = 0, []
    for line in lines:
        if not isinstance(line, dict) or not {
            'base_price', 'discount_pct', 'quantity'
        }.issubset(line):
            raise ValueError('Для расчёта нужны base_price, discount_pct, quantity')
        base = _positive_int(line['base_price'], 'base_price', MAX_PRICE)
        quantity = _positive_int(line['quantity'], 'quantity', MAX_QUANTITY)
        discount = line['discount_pct']
        if type(discount) is not int or not 0 <= discount <= 100:
            raise ValueError('Скидка должна быть целым процентом от 0 до 100')
        unit_price = (base * (100 - discount) + 50) // 100
        if unit_price == 0:
            raise ValueError('Бесплатные предложения не входят в покупку')
        line_total = unit_price * quantity
        priced_lines.append(dict(line, unit_price=unit_price,
                                 line_total=line_total))
        total += line_total
    return total, priced_lines


def check_purchase(state, player_id, lines, currency='COIN'):
    request = _normalize_request(player_id, lines, currency)
    player = state['players'].get(player_id)
    if player is None or not player['active']:
        raise ValueError('Игрок отсутствует или заблокирован')
    prepared, seen_items = [], set()
    for line in request['lines']:
        offer = state['offers'].get(line['offer_id'])
        if offer is None:
            raise ValueError('Предложение не найдено')
        if (not offer['active'] or offer['region'] != player['region']
                or offer['currency'] != currency):
            raise ValueError('Предложение недоступно по активности, региону или валюте')
        item = state['items'].get(offer['item_id'])
        if item is None:
            raise ValueError('Предмет не найден')
        if player['age'] < item['min_age']:
            raise ValueError('Возрастное ограничение')
        if item['item_type'] not in {'permanent', 'consumable'}:
            raise ValueError('Неизвестный тип предмета')
        if item['item_type'] == 'permanent' and line['quantity'] != 1:
            raise ValueError('Постоянный предмет покупается в количестве 1')
        if offer['item_id'] in seen_items:
            raise ValueError('Один предмет не должен повторяться через разные предложения')
        seen_items.add(offer['item_id'])
        prepared.append(dict(line, item_id=offer['item_id'],
                             item_type=item['item_type'],
                             base_price=offer['base_price'],
                             discount_pct=offer['discount_pct']))
    total, priced_lines = calculate_order_total(prepared)
    for line in priced_lines:
        if line['unit_price'] != line['expected_unit_price']:
            raise ValueError('Цена изменилась; требуется новое подтверждение')
    wallet = state['wallets'].get(player_id)
    if wallet is None or wallet['currency'] != currency:
        raise ValueError('Кошелёк отсутствует или его валюта отличается')
    if wallet['balance'] < total:
        raise ValueError('Недостаточно средств')
    owned = {entry['item_id'] for entry in state['inventory']
             if entry['player_id'] == player_id}
    if any(line['item_type'] == 'permanent' and line['item_id'] in owned
           for line in priced_lines):
        raise ValueError('Постоянный предмет уже принадлежит игроку')
    return total, priced_lines


def execute_purchase(state, player_id, lines, request_key, currency='COIN'):
    if not isinstance(request_key, str) or not request_key.strip() or len(request_key) > 128:
        raise ValueError('Ключ запроса должен содержать 1..128 символов')
    request = _normalize_request(player_id, lines, currency)
    previous = state['orders'].get(request_key)
    if previous is not None:
        if previous['request'] != request:
            raise ValueError('Ключ уже использован с другими параметрами')
        return deepcopy(state), deepcopy(previous['result'])

    total, priced_lines = check_purchase(state, player_id, lines, currency)
    new_state = deepcopy(state)
    order_id = new_state['next_order_id']
    new_state['next_order_id'] += 1
    new_state['wallets'][player_id]['balance'] -= total
    result_lines = []
    for position, line in enumerate(priced_lines, start=1):
        result_lines.append({
            'position': position, 'offer_id': line['offer_id'],
            'item_id': line['item_id'], 'quantity': line['quantity'],
            'base_price': line['base_price'], 'discount_pct': line['discount_pct'],
            'unit_price': line['unit_price'], 'line_total': line['line_total'],
        })
        new_state['inventory'].append({
            'order_id': order_id, 'position': position,
            'player_id': player_id, 'item_id': line['item_id'],
            'quantity': line['quantity'],
        })
    result = {'order_id': order_id, 'request_key': request_key,
              'player_id': player_id, 'currency': currency,
              'status': 'completed', 'total_amount': total, 'lines': result_lines}
    new_state['orders'][request_key] = {
        'request': deepcopy(request), 'result': deepcopy(result)
    }
    new_state['ledger'].append({'order_id': order_id, 'amount': -total})
    return new_state, result


def main():
    state = {
        'players': {1: {'nickname': 'PlayerOne', 'age': 20,
                        'region': 'RU', 'active': True}},
        'wallets': {1: {'currency': 'COIN', 'balance': 1000}},
        'items': {
            1: {'title': 'Косметический меч', 'item_type': 'permanent', 'min_age': 12},
            2: {'title': 'Зелье восстановления', 'item_type': 'consumable', 'min_age': 0},
        },
        'offers': {
            1: {'item_id': 1, 'region': 'RU', 'currency': 'COIN',
                'base_price': 300, 'discount_pct': 10, 'active': True},
            2: {'item_id': 2, 'region': 'RU', 'currency': 'COIN',
                'base_price': 100, 'discount_pct': 20, 'active': True},
        },
        'inventory': [], 'orders': {}, 'ledger': [], 'next_order_id': 1,
    }
    lines = [
        {'offer_id': 1, 'quantity': 1, 'expected_unit_price': 270},
        {'offer_id': 2, 'quantity': 3, 'expected_unit_price': 80},
    ]
    print('Начальный баланс:', state['wallets'][1]['balance'], 'COIN')
    total, _ = check_purchase(state, 1, lines)
    print('Стоимость корзины:', total, 'COIN')
    state, result = execute_purchase(state, 1, lines, 'demo-001')
    print('Результат покупки:')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print('Остаток:', state['wallets'][1]['balance'], 'COIN')
    print('Инвентарь:')
    print(json.dumps(state['inventory'], ensure_ascii=False, indent=2))
    state, repeated = execute_purchase(state, 1, lines, 'demo-001')
    print('Повтор запроса: заказ', repeated['order_id'],
          '; баланс', state['wallets'][1]['balance'],
          '; заказов', len(state['orders']),
          '; списаний', len(state['ledger']))


if __name__ == '__main__':
    main()
